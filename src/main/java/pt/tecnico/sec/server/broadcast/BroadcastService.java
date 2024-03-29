package pt.tecnico.sec.server.broadcast;

import pt.tecnico.sec.contract.ObtainLocationRequest;
import pt.tecnico.sec.server.ServerApplication;
import pt.tecnico.sec.server.database.DBLocationReport;

import java.util.HashMap;
import java.util.Map;

import static pt.tecnico.sec.Constants.F_SERVERS;

public class BroadcastService {
    private final ServerApplication _serverApp;
    private final BroadcastId _broadcastId;
    private final int _serverCount;

    private boolean _sentEcho = false;
    private boolean _sentReady = false;
    private boolean _delivered = false;
    private final BroadcastMessage[] _echos;
    private final BroadcastMessage[] _readys;

    private BroadcastMessage[] _delivers;
    private BroadcastMessage _request;

    public BroadcastService(ServerApplication serverApp, BroadcastId broadcastId) {
        _serverApp = serverApp;
        _broadcastId = broadcastId;
        _serverCount = _serverApp.getServerCount();
        _echos = new BroadcastMessage[_serverCount];
        _readys = new BroadcastMessage[_serverCount];
    }

    /* ========================================================== */
    /* ====[              Auxiliary functions               ]==== */
    /* ========================================================== */

    public boolean is_delivered() {
        return _delivered;
    }

    public BroadcastMessage[] get_delivers() {
        return _delivers;
    }

    public int countAcks() {
        int acks = 0;
        for (BroadcastMessage m : _delivers)
            if (m != null) acks++;
        return acks;
    }

    public BroadcastMessage searchForMajorityMessage(BroadcastMessage[] messages, int quorum) {
        // count the times a message appears
        Map<BroadcastMessage, Integer> counter = new HashMap<>();
        for (BroadcastMessage m : messages) {
            if (m == null) continue;
            Integer count = counter.get(m);
            if (count == null) counter.put(m, 1);
            else counter.replace(m, count+1);
        }
        // search for message that appears more than <quorum> times
        for (BroadcastMessage m : counter.keySet()) {
            if (counter.get(m) > quorum) return m;
        }
        return null;
    }

    public boolean validResponse(BroadcastMessage m) {
        if (_request == null) return false;
        if (_request.is_write()) {
            DBLocationReport request = _request.getDBLocationReport();
            Integer response = m.get_timestamp();

            return _request.get_broadcastId().equals(m.get_broadcastId()) && request.get_timestamp() == response;
        }
        else if (_request.is_read()) {
            ObtainLocationRequest request = _request.get_request();
            DBLocationReport response = m.get_report();
            return _request.get_broadcastId().equals(m.get_broadcastId()) &&
                    (response == null || response.get_userId() == request.get_userId()
                            && response.get_epoch() == request.get_epoch());
        }
        return false;
    }


    /* ========================================================== */
    /* ====[              Double Echo Broadcast             ]==== */
    /* ========================================================== */

    // Send SEND
    @SuppressWarnings("StatementWithEmptyBody")
    public void broadcastSend(BroadcastMessage m) {
        _delivers = new BroadcastMessage[_serverCount];
        _request = m;

        // Start threads to broadcast
        _serverApp.postToServers(m, "/broadcast-send");
        while (countAcks() <= (_serverCount + F_SERVERS) / 2) {
            // empty
        }
    }

    // Deliver SEND -> Send ECHO
    public void broadcastSENDDeliver(BroadcastMessage m) {
        if (!_sentEcho) {
            _sentEcho = true;
            _serverApp.postToServers(m, "/broadcast-echo");
        }
    }

    // Deliver ECHO -> Send READY
    public void broadcastECHODeliver(int id, BroadcastMessage m) {
        if (_echos[id] == null) _echos[id] = m;

        if (!_sentReady) {
            BroadcastMessage message = searchForMajorityMessage(_echos, (_serverCount+ F_SERVERS)/2);
            if (message != null) {
                _sentReady = true;
                _serverApp.postToServers(message, "/broadcast-ready");
            }
        }
    }

    // Deliver READY -> Send READY, Deliver response
    public boolean broadcastREADYDeliver(int id, BroadcastMessage m) {
        if (_readys[id] == null) _readys[id] = m;
        if (!_sentReady) {
            BroadcastMessage message = searchForMajorityMessage(_readys, F_SERVERS);
            if (message != null) {
                _sentReady = true;
                _serverApp.postToServers(message, "/broadcast-ready");
            }
            else return false; // for efficiency
        }
        if (!_delivered) {
            BroadcastMessage message = searchForMajorityMessage(_readys, 2* F_SERVERS);
            if (message != null) {
                _delivered = true;
                return true;
            }
        }
        return false;
    }

    // Received answer
    public void broadcastDeliver(int id, BroadcastMessage m) {
        if (_delivers[id] == null) _delivers[id] = m;
    }

    @Override
    public String toString() {
        return "BroadcastService{" +
                "_broadcastId=" + _broadcastId +
                ", _delivered=" + _delivered +
                '}';
    }
}
