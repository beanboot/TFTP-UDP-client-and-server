package server;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TFTPUDPServer {

    // initialise constants
    private static final int SERVER_PORT = 9000;
    private static final int DATA_SIZE = 512;
    private static final int TIMEOUT_MS = 2000;
    private static final int MAX_RETRIES = 5;

    public static void main(String[] args) throws SocketException {
        // open server socket
        try (DatagramSocket serverSocket = new DatagramSocket(SERVER_PORT)) {
            System.out.println("TFTP Server started on port " + SERVER_PORT);

            // while loop will run forever
            while (true) {
                // receive the request packet
                byte[] buffer = new byte[DATA_SIZE + 4];
                DatagramPacket requestPacket = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(requestPacket);

                // create new thread to handle the client
                new Thread(new TFTPUDPClientHandler(requestPacket)).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static class TFTPUDPClientHandler implements Runnable {

        private final DatagramPacket requestPacket;

        TFTPUDPClientHandler(DatagramPacket requestPacket) {
            this.requestPacket = requestPacket;
        }

        @Override
        public void run() {
            // open a new socket for the client
            try (DatagramSocket clientSocket = new DatagramSocket()) {
                // call the handleClientRequest method with the specific client's socket and request packet
                System.out.println("Client socket opened on port " + clientSocket.getLocalPort());
                handleClientRequest(clientSocket, requestPacket);
            } catch (IOException e) {
                System.err.println("Client handler error: " + e.getMessage());
            }
        }

        private void handleClientRequest(DatagramSocket clientSocket, DatagramPacket requestPacket) throws IOException {
            // extract the data, address, and port of the request packet
            byte[] data = Arrays.copyOfRange(requestPacket.getData(), 0, requestPacket.getLength());
            InetAddress clientAddress = requestPacket.getAddress();
            int clientPort = requestPacket.getPort();

            // reads the packet's opcode and calls the required read / write method
            try {
                if (data[1] == 1) {
                    handleReadRequest(data, clientSocket, clientAddress, clientPort);
                } else if (data[1] == 2) {
                    handleWriteRequest(data, clientSocket, clientAddress, clientPort);
                } else {
                    clientSocket.send(makeErrorPacket(clientAddress, clientPort, 4, "Illegal TFTP operation"));
                    System.out.println("Error packet sent, closing port " + clientSocket.getLocalPort());
                    clientSocket.close();
                }
            } catch (IOException e) {
                // sends a "file not found" error packet
                clientSocket.send(makeErrorPacket(clientAddress, clientPort, 1, "File not found"));
                System.out.println("Error packet sent, closing port " + clientSocket.getLocalPort());
                clientSocket.close();
            }
        }

        private void handleReadRequest(byte[] data, DatagramSocket clientSocket,InetAddress clientAddress, int clientPort) throws IOException {
            // extracts filename from packet, and initialises variables
            String filename = extractFilename(data);
            int blockNumber = 1;
            boolean finalPacketSent = false;
            DatagramPacket lastPacketSent = null;
            int retries = 0;

            // while loop will loop until the last packet is sent
            while (!finalPacketSent) {
                try {
                    // extracts data from file in directory and sends a data packet
                    byte[] fileData = extractFileData(blockNumber, filename);

                    lastPacketSent = makeDataPacket(fileData, clientAddress, clientPort, blockNumber);
                    clientSocket.send(lastPacketSent);

                    // checks if the packet sent was the last packet needed to be sent
                    if (fileData.length < DATA_SIZE) {
                        finalPacketSent = true;
                    }

                    // initialises variables to receive packet, and sets socket timeout timer
                    byte[] buffer = new byte[DATA_SIZE + 4];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    clientSocket.setSoTimeout(TIMEOUT_MS);
                    clientSocket.receive(packet);

                    data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

                    // checks if the received packet was an error packet
                    if (data[1] == 5) {
                        String errorMessage = extractErrorMessage(data);
                        System.out.println(errorMessage);
                        return;
                    } else {
                        // checks if the received packets block number corresponds correctly
                        int receivedBlockNumber = extractBlockNumber(buffer);
                        if (receivedBlockNumber != blockNumber) {
                            System.out.println("Block numbers don't match, ending connection...");
                            return;
                        }
                        // increments block number counter and resets retries to 0
                        blockNumber++;
                        retries = 0;
                    }
                } catch (SocketTimeoutException e) {
                    // will resend packet on timeout if max retries hasn't been reached
                    if (retries < MAX_RETRIES) {
                        retries++;
                        if (lastPacketSent != null) {
                            clientSocket.send(lastPacketSent);
                        }
                    } else {
                        System.out.println("Timeout occurred, ending connection...");
                        return;
                    }
                }
            }

            // closes the client socket
            System.out.println("Read request handled, closing port " + clientSocket.getLocalPort());
            clientSocket.close();
        }

        private void handleWriteRequest(byte[] data, DatagramSocket clientSocket, InetAddress clientAddress, int clientPort) throws IOException {
            // extracts filename from packet, and initialises variables
            String filename = extractFilename(data);
            int blockNumber = 0;
            boolean finalPacketReceived = false;

            // sends the acknowledgment to the request packet (with block number 0)
            clientSocket.send(makeAckPacket(clientAddress, clientPort, blockNumber));

            // while loop will loop until the final packet is received
            while (!finalPacketReceived) {
                try {
                    // initialises variables to receive packet, and sets the socket timeout timer
                    byte[] buffer = new byte[DATA_SIZE + 4];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    clientSocket.setSoTimeout(TIMEOUT_MS);
                    clientSocket.receive(packet);

                    data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

                    // checks if the packet is an error packet
                    if (data[1] == 5) {
                        String errorMessage = extractErrorMessage(data);
                        System.out.println(errorMessage);
                        return;
                    } else {
                        // checks if the received packet's block number corresponds with the counter
                        // (+1 because it started at 0)
                        int receivedBlockNumber = extractBlockNumber(data);
                        if (receivedBlockNumber == blockNumber + 1) {
                            // writeFromDataPacket method returns true if the packet received was < 516 bytes
                            // this means the final packet has been received
                            if (writeFromDataPacket(data, filename)) {
                                finalPacketReceived = true;
                            }

                            // increments block number counter and sends an ack packet
                            blockNumber++;
                            clientSocket.send(makeAckPacket(clientAddress, clientPort, blockNumber));
                        } else {
                            System.out.println("Block numbers don't match, ending connection...");
                            return;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout occurred, ending connection...");
                    return;
                }
            }

            // closes the client socket
            System.out.println("Write request handled, closing port " + clientSocket.getLocalPort());
            clientSocket.close();
        }

        // reads data from a file locally and returns it in a byte array
        private byte[] extractFileData(int blockNumber, String filename) throws IOException {
            byte[] data = new byte[DATA_SIZE];

            // uses FileInputStream to read data
            try (FileInputStream inputStream = new FileInputStream(filename)) {
                // uses block number to work out how many bytes have already been read
                long skipBytes = (long) (blockNumber - 1) * DATA_SIZE;
                inputStream.skip(skipBytes);

                int bytesRead = inputStream.read(data);

                // returns a shorter array if there isn't enough data to fill the whole array
                // this will be used to signify that this is the last packet
                if (bytesRead < data.length) {
                    byte[] trimmedData = new byte[bytesRead];
                    System.arraycopy(data, 0, trimmedData, 0, bytesRead);
                    return trimmedData;
                }
            }

            return data;
        }

        // reads the filename from the byte array and returns it as a string
        private String extractFilename(byte[] data) {
            int stringEndIndex = 2;

            // loops until it reaches the 0 byte
            while (stringEndIndex < data.length && data[stringEndIndex] != 0) {
                stringEndIndex++;
            }

            return new String(data, 2, stringEndIndex - 2, StandardCharsets.UTF_8);
        }

        // reads the error message from the byte array and returns it as a string
        private String extractErrorMessage(byte[] data) {
            int stringEndIndex = 4;

            // loops until it reaches the 0 byte
            while (stringEndIndex < data.length && data[stringEndIndex] != 0) {
                stringEndIndex++;
            }

            return new String(data, 4, stringEndIndex - 4, StandardCharsets.UTF_8);
        }

        // reads the (unsigned) block number from the byte array and returns it as an integer
        private int extractBlockNumber(byte[] data) {
            return ((data[2] & 0xFF) << 8 | (data[3] & 0xFF));
        }

        // constructs and returns an ack packet
        private DatagramPacket makeAckPacket(InetAddress packetAddress, int packetPort, int blockNumber) {
            byte[] packetData = new byte[4];

            packetData[0] = 0;
            packetData[1] = 4;
            packetData[2] = (byte) ((blockNumber >> 8) & 0xFF);
            packetData[3] = (byte) (blockNumber & 0xFF);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
            packet.setAddress(packetAddress);
            packet.setPort(packetPort);

            return packet;
        }

        // constructs and returns a data packet
        private DatagramPacket makeDataPacket(byte[] data, InetAddress packetAddress, int packetPort, int blockNumber) throws IOException {
            byte[] packetData = new byte[4 + data.length];

            packetData[0] = 0;
            packetData[1] = 3;
            packetData[2] = (byte) ((blockNumber >> 8) & 0xFF);
            packetData[3] = (byte) (blockNumber & 0xFF);
            // copies the data from the data array into the packetData array
            System.arraycopy(data, 0, packetData, 4, data.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
            packet.setAddress(packetAddress);
            packet.setPort(packetPort);

            System.out.println("Packet sent of size " + packetData.length + " with block number " + blockNumber);
            return packet;
        }

        // constructs and returns an error packet
        private DatagramPacket makeErrorPacket(InetAddress packetAddress, int packetPort, int errorCode, String errorMessage) {
            byte[] errorMessageBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
            byte[] packetData = new byte[4 + errorMessageBytes.length];

            packetData[0] = 0;
            packetData[1] = 5;
            // inserts the correct error code
            packetData[2] = (byte) ((errorCode >> 8) & 0xFF);
            packetData[3] = (byte) (errorCode & 0xFF);
            // copies the error message into the packetData array
            System.arraycopy(errorMessageBytes, 0, packetData, 4, errorMessageBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
            packet.setAddress(packetAddress);
            packet.setPort(packetPort);

            return packet;
        }

        // writes data from the received data packet onto a local file
        private boolean writeFromDataPacket(byte[] data, String filename) throws IOException {
            byte[] fileData = new byte[data.length - 4];
            System.arraycopy(data, 4, fileData, 0, data.length - 4);

            // uses FileOutputStream to write into a file locally
            try (FileOutputStream outputStream = new FileOutputStream(filename, true)) {
                outputStream.write(fileData);
            }

            // returns true if the file data is less than 512 bytes
            // this signifies that it was the last packet to be received
            return (fileData.length < DATA_SIZE);
        }
    }
}