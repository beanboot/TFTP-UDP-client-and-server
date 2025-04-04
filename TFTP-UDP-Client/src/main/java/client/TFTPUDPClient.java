package client;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

public class TFTPUDPClient {

    // initialises constants
    private static final int SERVER_PORT = 9000;
    private static final int DATA_SIZE = 512;
    private static final int TIMEOUT_MS = 2000;
    private static final int MAX_RETRIES = 5;

    public static void main(String[] args) throws IOException {

        TFTPUDPClient client = new TFTPUDPClient();

        if (args.length != 1) {
            System.out.println("Server address argument required");
            return;
        }

        // uses a scanner object to read the user's input
        Scanner scanner = new Scanner(System.in);
        // 1 correlates to a read request, 2 to a write request
        System.out.println("Type 1 to read a file, or 2 to write a file: ");
        int selection = scanner.nextInt();
        scanner.nextLine();
        // user input is stored as a string in the filename variable
        System.out.println("Please enter filename: ");
        String filename = scanner.nextLine();

        // opens a datagram socket and fetches the address passed into main
        DatagramSocket socket = new DatagramSocket(8999);
        InetAddress address = InetAddress.getByName(args[0]);

        // calls the appropriate method based on the user's selection
        if (selection == 1) {
            client.readRequest(filename, socket, address);
        } else if (selection == 2) {
            client.writeRequest(filename, socket, address);
        }
    }

    private void readRequest(String filename, DatagramSocket socket, InetAddress address) throws IOException {
        // initialises variables
        int blockNumber = 0;
        boolean finalPacketReceived = false;

        // constructs and sends the initial read request packet to the server
        byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
        byte[] requestPacketData = new byte[2 + filenameBytes.length];
        requestPacketData[0] = 0;
        requestPacketData[1] = 1;
        System.arraycopy(filenameBytes, 0, requestPacketData, 2, filenameBytes.length);
        DatagramPacket requestPacket = new DatagramPacket(requestPacketData, requestPacketData.length);
        requestPacket.setAddress(address);
        requestPacket.setPort(SERVER_PORT);
        socket.send(requestPacket);

        // while loop will loop until the final packet is received
        while (!finalPacketReceived) {
            try {
                // initialises variables to receive data/error packet, and sets socket timeout timer
                byte[] buffer = new byte[DATA_SIZE + 4];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(TIMEOUT_MS);
                socket.receive(packet);

                // grabs address and port from the received packet
                // this is required because the server will have opened a new socket for us
                InetAddress packetAddress = packet.getAddress();
                int packetPort = packet.getPort();
                byte[] data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

                // checks if the received packet is an error packet
                if (data[1] == 5) {
                    int errorCode = extractErrorCode(data);
                    if (errorCode == 1) {
                        // if the error code is 1 then the user can input a new filename
                        Scanner scanner = new Scanner(System.in);
                        System.out.println("File not found, please try again: ");
                        filename = scanner.nextLine();

                        // call the method with new filename
                        readRequest(filename, socket, address);
                    } else {
                        String errorMessage = extractErrorMessage(data);
                        System.out.println(errorMessage);
                    }
                    return;
                } else {
                    // checks that the received packet's block number aligns with the block number counter
                    int receivedBlockNumber = extractBlockNumber(data);
                    if (receivedBlockNumber == blockNumber + 1) {
                        // writeFromDataPacket method returns true if the packet received was < 516 bytes
                        // this means the final packet has been received
                        if (writeFromDataPacket(data, filename)) {
                            finalPacketReceived = true;
                        }

                        // increments block number counter and sends an ack packet
                        blockNumber++;
                        socket.send(makeAckPacket(packetAddress, packetPort, blockNumber));
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

        // closes the socket
        System.out.println("Read request complete");
        socket.close();
    }

    private void writeRequest(String filename, DatagramSocket socket, InetAddress address) throws IOException {
        // initialises variables
        int blockNumber = 1;
        boolean finalPacketSent = false;
        DatagramPacket lastPacketSent = null;
        int retries = 0;

        // constructs and sends the initial write request packet to the server
        byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
        byte[] requestPacketData = new byte[2 + filenameBytes.length];
        requestPacketData[0] = 0;
        requestPacketData[1] = 2;
        System.arraycopy(filenameBytes, 0, requestPacketData, 2, filenameBytes.length);
        DatagramPacket requestPacket = new DatagramPacket(requestPacketData, requestPacketData.length);
        requestPacket.setAddress(address);
        requestPacket.setPort(SERVER_PORT);
        socket.send(requestPacket);

        // while loop loops until the final packet is sent
        while (!finalPacketSent) {
            try {
                // initialises variables to receive ack/error packet, and sets socket timeout timer
                byte[] buffer = new byte[DATA_SIZE + 4];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(TIMEOUT_MS);
                socket.receive(packet);

                byte[] data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

                // checks if the received packet is an error packet
                if (data[1] == 5) {
                    String errorMessage = extractErrorMessage(data);
                    System.out.println(errorMessage);
                    return;
                } else {
                    // checks that the received packet's block number aligns with the block number counter
                    int receivedBlockNumber = extractBlockNumber(buffer);
                    if (receivedBlockNumber != blockNumber - 1) {
                        System.out.println("Block numbers don't match, ending connection...");
                        return;
                    }

                    // grabs address and port from the received packet
                    // this is required because the server will have opened a new socket for us
                    InetAddress packetAddress = packet.getAddress();
                    int packetPort = packet.getPort();

                    byte[] fileData;

                    // attempts to read from a local file
                    // allows the user to re-enter the filename if an IO exception is caught
                    try {
                        fileData = extractFileData(blockNumber, filename);
                    } catch (IOException e) {
                        Scanner scanner = new Scanner(System.in);
                        System.out.println("File not found, please try again: ");
                        filename = scanner.nextLine();

                        writeRequest(filename, socket, address);
                        return;
                    }

                    // sends a data packet using the data read above
                    lastPacketSent = makeDataPacket(fileData, packetAddress, packetPort, blockNumber);
                    socket.send(lastPacketSent);

                    if (fileData.length < DATA_SIZE) {
                        finalPacketSent = true;
                    }

                    // increments block number counter and resets the retries counter
                    blockNumber++;
                    retries = 0;
                }
            } catch (SocketTimeoutException e) {
                // will resend the last packet sent on timeout as long as max retries hasn't been exceeded
                if (retries < MAX_RETRIES) {
                    retries++;
                    if (lastPacketSent != null) {
                        socket.send(lastPacketSent);
                    }
                } else {
                    System.out.println("Timeout occurred, ending connection...");
                    return;
                }
            }
        }

        // socket is closed
        System.out.println("Write request complete");
        socket.close();
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

    // reads the (unsigned) error code from the byte array and returns it as an integer
    private int extractErrorCode(byte[] data) {
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
