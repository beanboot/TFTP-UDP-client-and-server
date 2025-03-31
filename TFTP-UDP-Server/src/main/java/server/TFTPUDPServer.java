package server;

import java.net.DatagramSocket;
import java.net.SocketException;

public class TFTPUDPServer extends Thread {

    protected DatagramSocket socket = null;

    public TFTPUDPServer() throws SocketException {
        this("TFTPUDPServer");
    }

    public TFTPUDPServer(String name) throws SocketException {
        super(name);
        socket = new DatagramSocket(9000);
    }

    @Override
    public void run() {

    }
}
