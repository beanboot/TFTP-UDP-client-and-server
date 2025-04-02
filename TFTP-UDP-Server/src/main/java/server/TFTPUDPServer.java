package server;

import javafx.scene.control.cell.TextFieldTreeCell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class TFTPUDPServer extends Thread {

    protected DatagramSocket socket = null;

    // constructors
    public TFTPUDPServer() throws SocketException {
        this("TFTPUDPServer");
    }

    public TFTPUDPServer(String name) throws SocketException {
        super(name);
        socket = new DatagramSocket(9000);
    }

    // thread run method
    @Override
    public void run() {

        // initialise variables
        byte[] recvBuf = new byte[516];
        int currentBlockNumber = 0;
        String filename = null;

        try {
            while (true) {
                // receive packet and extract IP, port, and data
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(packet);

                InetAddress recvPacketIP = packet.getAddress();
                int recvPacketPort = packet.getPort();
                byte[] data = packet.getData();

                // if statements that read the packet's opcode and send the appropriate packet in return, by calling the respective method
                if (data[1] == 1) {
                    filename = extractFilename(data);
                    currentBlockNumber = 1;
                    socket.send(readRequestPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber, filename));
                } else if (data[1] == 2) {
                    filename = extractFilename(data);
                    socket.send(writeRequestPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber, filename));
                    currentBlockNumber++;
                } else if (data[1] == 3) {
                    socket.send(dataPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber, filename));
                    currentBlockNumber++;
                } else if (data[1] == 4) {
                    socket.send(ackPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber, filename));
                    currentBlockNumber++;
                }
            }
        } catch (IOException e) {
            System.err.println(e);
        }

        socket.close();
    }

    // constructs and returns an ack packet
    private DatagramPacket makeAckPacket(InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber) {
        byte[] packetData = new byte[4];

        packetData[0] = 0;
        packetData[1] = 4;
        packetData[2] = (byte) ((currentBlockNumber & 0xFF) << 8);
        packetData[3] = (byte) (currentBlockNumber & 0xFF);

        DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
        packet.setAddress(recvPacketIP);
        packet.setPort(recvPacketPort);

        return packet;
    }

    // returns the data, from a file in this directory, in a byte array of appropriate length
    private byte[] extractFileData(int currentBlockNumber, String filename) throws IOException {
        byte[] fullFileData = new byte[512];

        try (FileInputStream inputStream = new FileInputStream(filename)) {
            int bytesRead = inputStream.read(fullFileData, ((currentBlockNumber - 1) * 512), 512);

            if (bytesRead < fullFileData.length) {
                byte[] trimmedFileData = new byte[bytesRead];
                System.arraycopy(fullFileData, 0, trimmedFileData, 0, bytesRead);
                return trimmedFileData;
            }
        }

        return fullFileData;
    }

    // returns the filename from a RRQ/WRQ packet's data
    private String extractFilename(byte[] data) {
        int stringEndIndex = 2;

        while (stringEndIndex < data.length && data[stringEndIndex] != 0) {
            stringEndIndex++;
        }

        return new String(data, 2, stringEndIndex - 2, StandardCharsets.UTF_8);
    }

    // returns the block number from a packet's data
    private int extractBlockNumber(byte[] data) {
        return ((data[2] & 0xFF) << 8 | (data[3] & 0xFF));
    }

    // constructs and returns a data packet, fills the data packet with the returned array of the extractFileData() method
    private DatagramPacket readRequestPacket(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber, String filename) throws IOException {
        byte[] fileData = extractFileData(currentBlockNumber, filename);
        byte[] packetData = new byte[4 + fileData.length];

        packetData[0] = 0;
        packetData[1] = 3;
        packetData[2] = (byte) ((currentBlockNumber & 0xFF) << 8);
        packetData[3] = (byte) (currentBlockNumber & 0xFF);
        System.arraycopy(fileData, 0, packetData, 4, fileData.length);

        DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
        packet.setAddress(recvPacketIP);
        packet.setPort(recvPacketPort);

        return packet;
    }

    // calls the makeAckPacket method after writing the contents of the received data packet into a file in this directory
    private DatagramPacket writeRequestPacket(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber, String filename) throws IOException {
        byte[] fileData = new byte[data.length - 4];
        System.arraycopy(data, 4, fileData, 0, data.length - 4);

        try (FileOutputStream outputStream = new FileOutputStream(filename, true)) {
            outputStream.write(fileData);
        }

        return (makeAckPacket(recvPacketIP, recvPacketPort, currentBlockNumber));
    }

    // calls the writeRequestPacket method after checking that the received data packet's block number matches the current block number counter
    private DatagramPacket dataPacket(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber, String filename) throws IOException {
        int packetBlockNumber = extractBlockNumber(data);
        DatagramPacket packet = null;

        if (packetBlockNumber == currentBlockNumber) {
            packet = writeRequestPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber, filename);
        } // else send error packet (error code 5)

        return packet;
    }

    // calls the readRequestPacket method after checking that the received ack packet's block number matches the current block number counter
    private DatagramPacket ackPacket(byte[] data, InetAddress recvPacketIP, int recvPacketPort, int currentBlockNumber, String filename) throws IOException {
        int packetBlockNumber = extractBlockNumber(data);
        DatagramPacket packet = null;

        if (packetBlockNumber == currentBlockNumber) {
            packet = readRequestPacket(data, recvPacketIP, recvPacketPort, currentBlockNumber + 1, filename);
        } // else send error packet (error code 5)

        return packet;
    }

    public static void main(String[] args) throws SocketException {
        new TFTPUDPServer().start();
    }
}
