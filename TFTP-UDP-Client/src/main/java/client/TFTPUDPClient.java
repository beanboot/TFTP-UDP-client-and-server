package client;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class TFTPUDPClient {
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            System.out.println("Server address required");
            return;
        }

        Scanner scanner = new Scanner(System.in);

        System.out.println("Type 1 to read a file, or 2 to write a file: ");
        int selection = scanner.nextInt();
        scanner.nextLine();

        if (selection == 0 || selection > 2) {
            System.out.println("Invalid input");
            return;
        }

        System.out.println("Please enter filename: ");
        String filename = scanner.nextLine();

        DatagramSocket socket = new DatagramSocket(8999);
        InetAddress address = InetAddress.getByName(args[0]);
        byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
        byte[] requestPacketData = new byte[2 + filenameBytes.length];

        requestPacketData[0] = 0;
        if (selection == 1) {
            requestPacketData[1] = 1;
        } else {
            requestPacketData[1] = 2;
        }
        System.arraycopy(filenameBytes, 0, requestPacketData, 2, filenameBytes.length);

        DatagramPacket requestPacket = new DatagramPacket(requestPacketData, requestPacketData.length);
        requestPacket.setAddress(address);
        requestPacket.setPort(9000);

        socket.send(requestPacket);
    }
}
