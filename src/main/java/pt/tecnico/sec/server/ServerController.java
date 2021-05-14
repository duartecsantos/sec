package pt.tecnico.sec.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pt.tecnico.sec.client.*;
import pt.tecnico.sec.healthauthority.ObtainUsersRequest;
import pt.tecnico.sec.healthauthority.UsersAtLocation;
import pt.tecnico.sec.server.exception.RecordAlreadyExistsException;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;

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
        ObtainLocationRequest request = _serverApp.decipherAndVerifyReportRequest(secureRequest, false);

        // Broadcast Read operation
        DBLocationReport dbLocationReport = _serverApp.broadcastRead(request);
        if (dbLocationReport == null)
            return null;

        SignedLocationReport report = new SignedLocationReport(dbLocationReport);

        // encrypt using same secret key and client/HA public key, sign using server private key
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(report);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes, false);
    }


    /* ========================================================== */
    /* ====[               Handle Secret Keys               ]==== */
    /* ========================================================== */

    @PostMapping("/secret-key")
    public SecureMessage newClientSecretKey(@RequestBody SecureMessage secureMessage) throws Exception {
        // decipher and verify message
        SecretKey newSecretKey = _serverApp.decipherAndVerifyKey(secureMessage, false);

        // save new key
        _serverApp.saveClientSecretKey(secureMessage.get_senderId(), newSecretKey);

        // Send secure response
        String response = "OK";
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        return _serverApp.cipherAndSignMessage(secureMessage.get_senderId(), bytes, false);
    }

    @PostMapping("/secret-key-server")
    public SecureMessage newServerSecretKey(@RequestBody SecureMessage secureMessage) throws Exception {
        // decipher and verify message
        SecretKey newSecretKey = _serverApp.decipherAndVerifyKey(secureMessage, true);

        // save new key
        _serverApp.saveServerSecretKey(secureMessage.get_senderId(), newSecretKey);

        // Send secure response
        String response = "OK";
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        return _serverApp.cipherAndSignMessage(secureMessage.get_senderId(), bytes, true);
    }

    /* ========================================================== */
    /* ====[                      Users                     ]==== */
    /* ========================================================== */

    @PostMapping("/submit-location-report")
    public SecureMessage reportLocation(@RequestBody SecureMessage secureMessage) throws Exception {
        // Decipher and check report
        DBLocationReport locationReport = _serverApp.decipherAndVerifyReport(secureMessage, false);

        // Check if already exists a report with the same userId and epoch
        int userId = locationReport.get_userId();
        int epoch = locationReport.get_epoch();
        if (_reportRepository.findReportByEpochAndUser(userId, epoch) != null)
            throw new RecordAlreadyExistsException("Report for userId " + userId + " and epoch " + epoch + " already exists.");

        // Broadcast write operation to other servers
        _serverApp.broadcastWrite(locationReport);

        // Send secure response
        String response = "OK";
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        return _serverApp.cipherAndSignMessage(secureMessage.get_senderId(), bytes, false);
    }

    @PostMapping("/request-proofs")
    public SecureMessage getWitnessProofs(@RequestBody SecureMessage secureRequest) throws Exception {
        // decipher and verify request
        WitnessProofsRequest request = _serverApp.decipherAndVerifyProofsRequest(secureRequest, false);

        // find requested reports
        int witnessId = request.get_userId();
        List<DBLocationReport> dbLocationReports = new ArrayList<>();
        for (int epoch : request.get_epochs())
            dbLocationReports.addAll( _reportRepository.findReportsByEpochAndWitness(witnessId, epoch) );

        // convert reports to client form
        List<SignedLocationReport> reports = new ArrayList<>();
        for (DBLocationReport dbLocationReport : dbLocationReports)
            reports.add( new SignedLocationReport(dbLocationReport) );

        // extract requested proofs
        List<LocationProof> locationProofs = new ArrayList<>();
        for (SignedLocationReport report : reports)
            locationProofs.add( report.get_witness_proof(witnessId) );

        // encrypt using same secret key and client public key, sign using server private key
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(locationProofs);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes, false);

    }

    /* ========================================================== */
    /* ====[                 Health Authority               ]==== */
    /* ========================================================== */

    @PostMapping("/users") // FIXME regular operation? ou pode ser atomic? perguntar
    public SecureMessage getUsers(@RequestBody SecureMessage secureRequest) throws Exception {
        // decipher and verify request
        ObtainUsersRequest request = _serverApp.decipherAndVerifyUsersRequest(secureRequest, false);
        int ep = request.get_epoch();
        Location loc = request.get_location();

        List<SignedLocationReport> reports = new ArrayList<>();

        // set of read operations
        int userCount = _serverApp.getUserCount();
        for (int id = 0; id < userCount; id++) {
            ObtainLocationRequest userReportRequest = new ObtainLocationRequest(id, ep);
            DBLocationReport dbLocationReport = _serverApp.broadcastRead(userReportRequest);
            // filter requested reports
            if (dbLocationReport != null && dbLocationReport.get_location().equals(loc))
                reports.add(new SignedLocationReport(dbLocationReport));
        }

        // encrypt and send response
        UsersAtLocation response = new UsersAtLocation(loc, ep, reports);
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        return _serverApp.cipherAndSignMessage(secureRequest.get_senderId(), bytes, false);
    }

    /* ========================================================== */
    /* ====[               Regular Registers                ]==== */
    /* ========================================================== */

    @PostMapping("/broadcast-write")
    public SecureMessage broadcastWrite(@RequestBody SecureMessage secureMessage) throws Exception {
        System.out.println("Received Write broadcast");

        // Decipher and check report
        DBLocationReport locationReport = _serverApp.decipherAndVerifyDBReport(secureMessage, true);
        int senderId = secureMessage.get_senderId();
        if (senderId != _serverApp.getId()) _serverApp.serverSecretKeyUsed(senderId);

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
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(timestamp);
        return _serverApp.cipherAndSignMessage(senderId, bytes, true);
    }

    @PostMapping("/broadcast-read")
    public SecureMessage broadcastRead(@RequestBody SecureMessage secureRequest) throws Exception {
        System.out.println("Received Read broadcast");

        // decipher and verify request TODO use different ids for servers, check sender is server
        ObtainLocationRequest request = _serverApp.decipherAndVerifyReportRequest(secureRequest, true);
        int senderId = secureRequest.get_senderId();
        if (senderId != _serverApp.getId()) _serverApp.serverSecretKeyUsed(senderId);

        // find requested report
        DBLocationReport report = _reportRepository.findReportByEpochAndUser(request.get_userId(), request.get_epoch());

        // encrypt using same secret key and client/HA public key, sign using server private key
        ObjectMapper objectMapper = new ObjectMapper();
        byte[] bytes = objectMapper.writeValueAsBytes(report);
        return _serverApp.cipherAndSignMessage(senderId, bytes, true);
    }


}
