package pt.tecnico.sec.client.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.tecnico.sec.server.database.DBProofData;

@SuppressWarnings("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProofData {

    private Location _location;
    private int _proverId;
    private int _witnessId;
    private int _epoch;
    private String _type;

    public ProofData() {}

    public ProofData(Location location, int proverId, int witnessId, int epoch, String type) {
        _location = location;
        _proverId = proverId;
        _witnessId = witnessId;
        _epoch = epoch;
        _type = type;
    }

    // convert from client version
    public ProofData(DBProofData dbProofData) {
        _location = new Location( dbProofData.get_DB_location() );
        _proverId = dbProofData.get_proverId();
        _witnessId = dbProofData.get_witnessId();
        _epoch = dbProofData.get_epoch();
        _type = dbProofData.get_type();
    }

    public Location get_location() {
        return _location;
    }

    public void set_location(Location _location) {
        this._location = _location;
    }

    public int get_proverId() {
        return _proverId;
    }

    public void set_proverId(int _proverId) {
        this._proverId = _proverId;
    }

    public int get_witnessId() {
        return _witnessId;
    }

    public void set_witnessId(int _witnessId) {
        this._witnessId = _witnessId;
    }

    public int get_epoch() {
        return _epoch;
    }

    public void set_epoch(int _epoch) {
        this._epoch = _epoch;
    }

    public String get_type() {
        return _type;
    }

    public void set_type(String _type) {
        this._type = _type;
    }

    @Override
    public String toString() {
        return "ProofData{" +
                "_location=" + _location +
                ", _proverId=" + _proverId +
                ", _witnessId=" + _witnessId +
                ", _epoch=" + _epoch +
                ", _type='" + _type + '\'' +
                '}';
    }
}