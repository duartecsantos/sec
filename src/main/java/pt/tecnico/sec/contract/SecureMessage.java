package pt.tecnico.sec.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.tecnico.sec.keys.AESKeyGenerator;
import pt.tecnico.sec.keys.CryptoRSA;
import pt.tecnico.sec.server.broadcast.BroadcastMessage;

import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

@SuppressWarnings("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecureMessage {

    private int _senderId;
    private String _cipheredMessage;
    private String _signature;

    public SecureMessage() {}

    public SecureMessage(int senderId, String cipheredMessage, String signature) {
        _senderId = senderId;
        _cipheredMessage = cipheredMessage;
        _signature = signature;
    }

    public SecureMessage(int senderId, Message message, SecretKey cipherKey, PrivateKey signKey) throws Exception {
        _senderId = senderId;

        // encrypt message with given secret key
        byte[] messageBytes = ObjectMapperHandler.writeValueAsBytes(message);
        _cipheredMessage = AESKeyGenerator.encrypt(messageBytes, cipherKey);

        // sign message with given private sign key
        _signature = CryptoRSA.sign(messageBytes, signKey);
    }

    public SecureMessage(int senderId, byte[] messageBytes, SecretKey cipherKey, PrivateKey signKey) throws Exception {
        _senderId = senderId;

        // encrypt message with given secret key
        _cipheredMessage = AESKeyGenerator.encrypt(messageBytes, cipherKey);

        // sign message with given private sign key
        _signature = CryptoRSA.sign(messageBytes, signKey);
    }

    // Used to exchange secret Keys - keyToSend is the new secretKey
    public SecureMessage(int senderId, SecretKey keyToSend, PublicKey cipherKey, PrivateKey signKey) throws Exception {
        _senderId = senderId;

        // write secret key in bytes
        byte[] encodedKey = keyToSend.getEncoded();

        // encrypt message with given public cipher key
        _cipheredMessage = CryptoRSA.encrypt(encodedKey, cipherKey);

        // sign message with given private sign key
        _signature = CryptoRSA.sign(encodedKey, signKey);
    }

    // Used to respond to secret Key exchange
    public SecureMessage(int senderId, Message message, PublicKey cipherKey, PrivateKey signKey) throws Exception {
        _senderId = senderId;

        // encrypt message with given public cipher key
        byte[] messageBytes = ObjectMapperHandler.writeValueAsBytes(message);
        _cipheredMessage = CryptoRSA.encrypt(messageBytes, cipherKey);

        // sign message with given private sign key
        _signature = CryptoRSA.sign(messageBytes, signKey);
    }

    // Used in broadcasts
    public SecureMessage(int senderId, BroadcastMessage message, SecretKey cipherKey, PrivateKey signKey) throws Exception {
        _senderId = senderId;

        // encrypt message with given secret key
        byte[] messageBytes = ObjectMapperHandler.writeValueAsBytes(message);
        _cipheredMessage = AESKeyGenerator.encrypt(messageBytes, cipherKey);

        // sign message with given private sign key
        _signature = CryptoRSA.sign(messageBytes, signKey);
    }

    public int get_senderId() {
        return _senderId;
    }

    public void set_senderId(int _senderId) {
        this._senderId = _senderId;
    }

    public String get_cipheredMessage() {
        return _cipheredMessage;
    }

    public void set_cipheredMessage(String _cipheredMessage) {
        this._cipheredMessage = _cipheredMessage;
    }

    public String get_signature() {
        return _signature;
    }

    public void set_signature(String _signature) {
        this._signature = _signature;
    }

    @Override
    public String toString() {
        return "SecureMessage{" +
                ", _senderId=" + _senderId +
                ", _cipheredMessage='" + _cipheredMessage + '\'' +
                ", _signature='" + _signature + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SecureMessage that = (SecureMessage) o;
        return _senderId == that._senderId && Objects.equals(_cipheredMessage, that._cipheredMessage) && Objects.equals(_signature, that._signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_senderId, _cipheredMessage, _signature);
    }

    /* ========================================================== */
    /* ====[             Ciphers and Signatures             ]==== */
    /* ========================================================== */

    public Message decipherAndVerifyMessage(SecretKey decipherKey, PublicKey verifyKey) throws Exception {
        byte[] messageBytes = decipher(decipherKey);
        verify(messageBytes, verifyKey);
        return ObjectMapperHandler.getMessageFromBytes(messageBytes);
    }

    public byte[] decipherAndVerify(SecretKey decipherKey, PublicKey verifyKey) throws Exception {
        byte[] messageBytes = decipher(decipherKey);
        verify(messageBytes, verifyKey);
        return messageBytes;
    }

    public SecretKey decipherAndVerifyKey(PrivateKey decipherKey, PublicKey verifyKey) throws Exception {
        byte[] encodedKey = CryptoRSA.decrypt(_cipheredMessage, decipherKey);
        verify(encodedKey, verifyKey);

        // get secret key from bytes
        return AESKeyGenerator.fromEncoded(encodedKey);
    }

    public Message decipherAndVerifyMessage(PrivateKey decipherKey, PublicKey verifyKey) throws Exception {
        byte[] messageBytes = CryptoRSA.decrypt(_cipheredMessage, decipherKey);
        verify(messageBytes, verifyKey);
        return ObjectMapperHandler.getMessageFromBytes(messageBytes);
    }

    public byte[] decipher(SecretKey decipherKey) throws Exception {
        return AESKeyGenerator.decrypt(_cipheredMessage, decipherKey);
    }

    public void verify(byte[] messageBytes, PublicKey verifyKey) {
        if (_signature == null || !CryptoRSA.verify(messageBytes, _signature, verifyKey))
            throw new IllegalArgumentException("Signature verify failed!");
    }

    public BroadcastMessage decipherAndVerifyBroadcastMessage(SecretKey decipherKey, PublicKey verifyKey) throws Exception {
        byte[] messageBytes = decipher(decipherKey);
        verify(messageBytes, verifyKey);
        return ObjectMapperHandler.getBroadcastMessageFromBytes(messageBytes);
    }
}
