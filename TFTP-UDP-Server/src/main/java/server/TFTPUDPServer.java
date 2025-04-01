package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
        byte[] recvBuf = new byte[512];
        int currentBlockNumber = 0;

        try {
            while (true) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(packet);

                InetAddress recvPacketIP = packet.getAddress();
                int recvPacketPort = packet.getPort();
                byte[] data = packet.getData();

                if (data[1] == 1) {
                    readRequest(data, recvPacketIP, recvPacketPort, currentBlockNumber);
                } else if (data[1] == 2) {
                    writeRequest(data, recvPacketIP, recvPacketPort, currentBlockNumber);
                }


            }
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    private void readRequest(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber) {}

    private void writeRequest(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber) {}
}
