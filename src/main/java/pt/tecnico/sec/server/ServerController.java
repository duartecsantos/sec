package pt.tecnico.sec.server;

import org.apache.tomcat.jni.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pt.tecnico.sec.ObjectMapperHandler;
import pt.tecnico.sec.client.*;
import pt.tecnico.sec.healthauthority.ObtainUsersRequest;
import pt.tecnico.sec.healthauthority.UsersAtLocation;
import pt.tecnico.sec.server.exception.RecordAlreadyExistsException;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static pt.tecnico.sec.Constants.OK;

@SuppressWarnings("AccessStaticViaInstance")
@RestController
public class ServerController {

    private static ServerApplication _serverApp;
    private static ReportRepository _reportRepository;

    @Autowired
    private ServerController(ServerApplication serverApp, ReportRepository reportRepository) {
        _serverApp = serverApp;
        _reportRepository = reportRepository;
    }

    /* ========================================================== */
    /* ====[                     General                    ]==== */
    /* ========================================================== */

    @PostMapping("/obtain-location-report")
    public SecureMessage getLocationClient(@RequestBody SecureMessage secureRequest) throws Exception {
        // decipher and verify request
        ObtainLocationRequest request = _serverApp.decipherAndVerifyReportRequest(secureRequest);

        // broadcast Read operation
        _serverApp.refreshServerSecretKeys();
        DBLocationReport dbLocationReport = _serverApp.doubleEchoBroadcastRead(request);
        if (dbLocationReport == null)
            return null;

        SignedLocationReport report = new SignedLocationReport(dbLocationReport);

        // encrypt using same secret key and client/HA public key, sign using server private key
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(report);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes);
    }

    /* ========================================================== */
    /* ====[                      Users                     ]==== */
    /* ========================================================== */

    @PostMapping("/submit-location-report")
    public SecureMessage reportLocation(@RequestBody SecureMessage secureMessage) throws Exception {
        // Decipher and check report
        DBLocationReport locationReport = _serverApp.decipherAndVerifyReport(secureMessage);

        // Check if already exists a report with the same userId and epoch
        int userId = locationReport.get_userId();
        int epoch = locationReport.get_epoch();
        if (_reportRepository.findReportByEpochAndUser(userId, epoch) != null)
            throw new RecordAlreadyExistsException("Report for userId " + userId + " and epoch " + epoch + " already exists.");

        // Broadcast write operation to other servers
        _serverApp.refreshServerSecretKeys();
        _serverApp.doubleEchoBroadcastWrite(locationReport);

        // Send secure response
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(OK);
        return _serverApp.cipherAndSignMessage(secureMessage.get_senderId(), bytes);
    }

    @PostMapping("/request-proofs")
    public SecureMessage getWitnessProofs(@RequestBody SecureMessage secureRequest) throws Exception {
        // decipher and verify request
        WitnessProofsRequest request = _serverApp.decipherAndVerifyProofsRequest(secureRequest);
        int witnessId = request.get_userId();
        Set<Integer> epochs = request.get_epochs();

        List<LocationProof> locationProofs = new ArrayList<>();

        // set of read operations
        int userCount = _serverApp.getUserCount();
        for (int id = 0; id < userCount; id++) {
            if (id == witnessId) continue; // user will never be a witness of itself
            for (int ep : epochs) {
                ObtainLocationRequest userReportRequest = new ObtainLocationRequest(id, ep);
                _serverApp.refreshServerSecretKeys();
                DBLocationReport dbLocationReport = _serverApp.doubleEchoBroadcastRead(userReportRequest);

                // filter requested reports
                if (dbLocationReport == null) continue;
                DBLocationProof proof = dbLocationReport.get_witness_proof(witnessId);
                if (proof != null)
                    locationProofs.add(new LocationProof(proof));
            }
        }

        // encrypt and send response
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(locationProofs);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes);

    }

    /* ========================================================== */
    /* ====[                 Health Authority               ]==== */
    /* ========================================================== */

    @SuppressWarnings("EqualsBetweenInconvertibleTypes")
    @PostMapping("/users") // FIXME : regular operation? ou pode ser atomic? perguntar
    public SecureMessage getUsers(@RequestBody SecureMessage secureRequest) throws Exception {
        // decipher and verify request
        ObtainUsersRequest request = _serverApp.decipherAndVerifyUsersRequest(secureRequest);
        int ep = request.get_epoch();
        Location loc = request.get_location();

        List<SignedLocationReport> reports = new ArrayList<>();

        // set of read operations
        int userCount = _serverApp.getUserCount();
        for (int id = 0; id < userCount; id++) {
            ObtainLocationRequest userReportRequest = new ObtainLocationRequest(id, ep);
            _serverApp.refreshServerSecretKeys();
            DBLocationReport dbLocationReport = _serverApp.doubleEchoBroadcastRead(userReportRequest);
            // filter requested reports
            if (dbLocationReport != null && dbLocationReport.get_location().equals(loc))
                reports.add(new SignedLocationReport(dbLocationReport));
        }

        // encrypt and send response
        UsersAtLocation response = new UsersAtLocation(loc, ep, reports);
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(response);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes);
    }


    /* ========================================================== */
    /* ====[               Handle Secret Keys               ]==== */
    /* ========================================================== */

    @PostMapping("/secret-key")
    public SecureMessage newSecretKey(@RequestBody SecureMessage secureMessage) throws Exception {
        // decipher and verify message
        SecretKey newSecretKey = _serverApp.decipherAndVerifyKey(secureMessage);

        // save new key
        _serverApp.saveSecretKey(secureMessage.get_senderId(), newSecretKey);

        // Send secure response
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(OK);
        return _serverApp.cipherAndSignMessage(secureMessage.get_senderId(), bytes);
    }

    @GetMapping("/refresh-secret-keys")
    public void refreshSecretKeys() throws Exception {
        // send refresh request to other servers
        _serverApp.refreshServerSecretKeys();
    }


    /* ========================================================== */
    /* ====[              Double Echo Broadcast             ]==== */
    /* ========================================================== */

    /* ====[                   W R I T E                    ]==== */


    @PostMapping("/doubleEchoBroadcast-send")
    public void doubleEchoBroadcastSend(@RequestBody SecureMessage message) throws Exception {
        int senderId = message.get_senderId();
        System.out.println("[*] Received a @SEND Request from " + senderId);
        BroadcastWrite bw = _serverApp.decipherAndVerifyBroadcastWrite(message);
        _serverApp.doubleEchoBroadcastSendDeliver(bw);
    }

    @PostMapping("/doubleEchoBroadcast-echo")
    public void doubleEchoBroadcastEcho(@RequestBody SecureMessage secureMessage) throws Exception {
        int senderId = secureMessage.get_senderId();
        System.out.println("[*] Received an @ECHO Request from " + senderId);
        BroadcastWrite bw = _serverApp.decipherAndVerifyBroadcastWrite(secureMessage);
        _serverApp.doubleEchoBroadcastEchoDeliver(secureMessage.get_senderId()-1000, bw);
    }

    @PostMapping("/doubleEchoBroadcast-ready")
    public void doubleEchoBroadcastReady(@RequestBody SecureMessage secureMessage) throws Exception {
        int senderId = secureMessage.get_senderId();
        System.out.println("[*] Received a @READY Request from " + senderId);
        BroadcastWrite bw = _serverApp.decipherAndVerifyBroadcastWrite(secureMessage);
        boolean delivered = _serverApp.doubleEchoBroadcastReadyDeliver(secureMessage.get_senderId()-1000, bw);
        if (delivered) writeLocationReport(bw);

    }

    public void writeLocationReport(BroadcastWrite bw) throws Exception {
        // Decipher and check report
        DBLocationReport locationReport = _serverApp.verifyBroadcastWrite(bw);

        int epoch = locationReport.get_epoch();
        int userId = locationReport.get_userId();
        int timestamp = locationReport.get_timestamp();
        int mytimestamp = 0;

        // Update database
        DBLocationReport mylocationReport = _reportRepository.findReportByEpochAndUser(userId, epoch);
        if (mylocationReport != null)
            mytimestamp = mylocationReport.get_timestamp();

        if (timestamp > mytimestamp){
            if (mylocationReport != null) _reportRepository.delete(mylocationReport);
            _reportRepository.save(locationReport);
        }

        // Encrypt and send response
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(timestamp);
        _serverApp.voidPostToServer(bw.get_originalId()-1000, bytes, "/doubleEchoBroadcast-deliver");
    }

    @PostMapping("/doubleEchoBroadcast-deliver")
    public void doubleEchoBroadcastDeliver(@RequestBody SecureMessage secureMessage) throws Exception {
        System.out.println("[*] Received a @DELIVER Request from " + secureMessage.get_senderId());
        int timestamp = _serverApp.decipherAndVerifyServerDeliver(secureMessage);
        _serverApp.doubleEchoBroadcastDeliver(secureMessage.get_senderId()-1000, timestamp);
    }


    /* ====[                    R E A D                     ]==== */

    @PostMapping("/doubleEchoBroadcast-send-read")
    public void doubleEchoBroadcastSend_read(@RequestBody SecureMessage message) throws Exception {
        int senderId = message.get_senderId();
        System.out.println("[*] Received a @SEND Request from " + senderId);
        BroadcastRead br = _serverApp.decipherAndVerifyBroadcastRead(message);
        _serverApp.doubleEchoBroadcastSendDeliver_read(br);
    }

    @PostMapping("/doubleEchoBroadcast-echo-read")
    public void doubleEchoBroadcastEcho_read(@RequestBody SecureMessage secureMessage) throws Exception {
        int senderId = secureMessage.get_senderId();
        System.out.println("[*] Received an @ECHO Request from " + senderId);
        BroadcastRead br = _serverApp.decipherAndVerifyBroadcastRead(secureMessage);
        _serverApp.doubleEchoBroadcastEchoDeliver_read(secureMessage.get_senderId()-1000, br);
    }

    @PostMapping("/doubleEchoBroadcast-ready-read")
    public void doubleEchoBroadcastReady_read(@RequestBody SecureMessage secureMessage) throws Exception {
        int senderId = secureMessage.get_senderId();
        System.out.println("[*] Received a @READY Request from " + senderId);
        BroadcastRead br = _serverApp.decipherAndVerifyBroadcastRead(secureMessage);
        boolean delivered = _serverApp.doubleEchoBroadcastReadyDeliver_read(secureMessage.get_senderId()-1000, br);
        if (delivered) readLocationReport(br);

    }

    public void readLocationReport(BroadcastRead br) throws Exception {
        System.out.println("[*] Received Read Broadcast");

        // Decipher and check request
        ObtainLocationRequest locationRequest = _serverApp.verifyBroadcastRead(br);

        int epoch = locationRequest.get_epoch();
        int userId = locationRequest.get_userId();

        // Find requested report
        DBLocationReport report = _reportRepository.findReportByEpochAndUser(userId, epoch);

        // Encrypt and send response
        byte[] bytes = ObjectMapperHandler.writeValueAsBytes(report);
        _serverApp.voidPostToServer(br.get_originalId()-1000, bytes, "/doubleEchoBroadcast-deliver-read");
    }

    @PostMapping("/doubleEchoBroadcast-deliver-read")
    public void doubleEchoBroadcastDeliver_read(@RequestBody SecureMessage secureMessage) throws Exception {
        System.out.println("[*] Received a @DELIVER Request from " + secureMessage.get_senderId());
        DBLocationReport report = _serverApp.decipherAndVerifyServerDeliver_read(secureMessage);
        _serverApp.doubleEchoBroadcastDeliver_read(secureMessage.get_senderId()-1000, report);
    }

}
