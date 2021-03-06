package com.btcontract.wallet.lightning

import com.softwaremill.quicklens._
import org.bitcoinj.core.{Sha256Hash, ECKey}
import com.btcontract.wallet.Utils.{Bytes, app}
import com.btcontract.wallet.lightning.{JavaTools => jt}
import com.btcontract.wallet.lightning.Tools.{ecdh, toPkt}
import com.btcontract.wallet.lightning.crypto.AeadChacha20
import com.btcontract.wallet.helper.Websocket
import org.bitcoinj.core.Utils.readUint32


object Decryptor {
  def header(buf1: Bytes, state: Decryptor) = if (buf1.length < 20) state.copy(buffer = buf1) else {
    val headLen = state.chacha.decrypt(buf1.slice(4, 20), jt writeUInt64 state.nonce, buf1 take 4, Array.empty) take 4
    add(Decryptor(state.chacha, state.nonce + 1, Array.empty, Some(readUint32(headLen, 0).toInt), state.bodies), buf1 drop 20)
  }

  def body(buf1: Bytes, state: Decryptor, headLen: Int) = if (buf1.length < headLen + 16) state.copy(buffer = buf1) else {
    val plain = state.chacha.decrypt(buf1.slice(headLen, headLen + 16), jt writeUInt64 state.nonce, buf1 take headLen, Array.empty)
    add(Decryptor(state.chacha, state.nonce + 1, Array.empty, None, state.bodies :+ plain), buf1 drop headLen + 16)
  }

  def add(state: Decryptor, data: Bytes): Decryptor = if (data.isEmpty) state else state match {
    case Decryptor(_, _, buffer, Some(headLen), bodies) => body(jt.concat(buffer, data), state, headLen)
    case Decryptor(_, _, buffer, None, _) => header(jt.concat(buffer, data), state)
  }
}

case class Encryptor(chacha: AeadChacha20, nonce: Long)
case class Decryptor(chacha: AeadChacha20, nonce: Long, buffer: Bytes = Array.empty,
                     header: Option[Int] = None, bodies: Vector[Bytes] = Vector.empty)

trait AuthState
case class SessionData(theirSesKey: Bytes, enc: Encryptor, dec: Decryptor) extends AuthState
case class NormalData(sesData: SessionData, theirNodeKey: ECKey) extends AuthState

abstract class AuthHandler(sesKey: ECKey, sock: Websocket)
extends StateMachine[AuthState]('WaitForSesKey :: Nil, null) {
  def respond(data: Bytes, enc: Encryptor) = jt writeUInt32 data.length.toLong match { case header =>
    val (ciphertext1, mac1) = enc.chacha.encrypt(jt writeUInt64 enc.nonce, header, Array.emptyByteArray)
    val (ciphertext2, mac2) = enc.chacha.encrypt(jt writeUInt64 enc.nonce + 1, data, Array.emptyByteArray)
    sock send jt.concat(ciphertext1, mac1, ciphertext2, mac2)
    enc.modify(_.nonce).using(_ + 2)
  }

  def sendToChannel(pack: proto.pkt)
  def doProcess(change: Any) = (data, change, state) match {
    // Presumably sent our handshake, waiting for their response
    case (null, msg: Bytes, 'WaitForSesKey :: rest) =>
      val theirSesPubKey = msg.slice(4, 33 + 4)

      // Generate shared secret and encryption keys
      val sharedSecret = ecdh(theirSesPubKey, sesKey.getPrivKeyBytes)
      val sendingKey = Sha256Hash hash jt.concat(sharedSecret, sesKey.getPubKey)
      val receivingKey = Sha256Hash hash jt.concat(sharedSecret, theirSesPubKey)
      val decryptor = Decryptor(new AeadChacha20(receivingKey), 0)
      val encryptor = Encryptor(new AeadChacha20(sendingKey), 0)

      // Create my authenticate in return for theirs
      val pubKey = Tools bytes2ProtoPubkey app.LNData.idKey.getPubKey
      val sig = Tools ts2Signature app.LNData.idKey.sign(Sha256Hash twiceOf theirSesPubKey)
      val authPkt = toPkt(new proto.authenticate.Builder node_id pubKey session_sig sig).encode
      become(SessionData(theirSesPubKey, respond(authPkt, encryptor), decryptor), 'WaitForAuth)

    // Sent our auth data, waiting for their respected auth data
    case (s: SessionData, chunk: Bytes, 'WaitForAuth :: rest) =>
      val dec1 = Decryptor.add(s.dec, chunk)

      dec1.bodies match {
        case firstBody +: tail =>
          val protoAuth = proto.pkt.ADAPTER.decode(firstBody).auth
          val theirSignature = Tools signature2Ts protoAuth.session_sig
          val theirNodeKey = ECKey fromPublicOnly protoAuth.node_id.key.toByteArray
          val sd1 = s.modify(_.dec.bodies).setTo(tail).modify(_.dec.header) setTo None
          theirNodeKey.verifyOrThrow(Sha256Hash twiceOf sesKey.getPubKey, theirSignature)
          become(NormalData(sd1, theirNodeKey), 'Normal)
          // In case if decryptor tail is not empty
          process(Array.emptyByteArray)

        // Accumulate chunks until we get a complete message
        case _ => stayWith(s.modify(_.dec) setTo dec1)
      }

    // Successfully authorized, now waiting for messages
    // Also just process remaining messages if chunk is empty
    case (n: NormalData, chunk: Bytes, 'Normal :: rest) =>
      val dec1 = Decryptor.add(n.sesData.dec, chunk)

      dec1.bodies match {
        case bodies if bodies.nonEmpty =>
          val dec2 = dec1.copy(header = None, bodies = Vector.empty)
          bodies map proto.pkt.ADAPTER.decode foreach sendToChannel
          stayWith(n.modify(_.sesData.dec) setTo dec2)

        // Again accumulate chunks until we get a complete message
        case _ => stayWith(n.modify(_.sesData.dec) setTo dec1)
      }

    // Got a request to send a packet to counterparty
    case (n: NormalData, message: AnyRef, 'Normal :: rest) =>
      val enc1 = respond(toPkt(message).encode, n.sesData.enc)
      stayWith(n.modify(_.sesData.enc) setTo enc1)

    case (_, something, _) =>
      // Let know if received an unhandled message in some state
      println(s"Unhandled $something in AuthHandler at $state : $data")
  }
}