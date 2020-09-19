import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Temp {
    public static void main(String[] args) {
        //Create a Socket with ip and port number
        Socket s = null;
        try {
            s = new Socket("localhost", 3456);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Get input from user
        Scanner in = new Scanner(System.in);
        System.out.println("Please enter a message");
        String clientMessage = in.nextLine() + "\r\n";

        //Make a printwriter and write the message to the socket
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(s.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        writer.println(clientMessage); // <- println
        writer.flush();                // <- flush

        //StreamReader to read the response from the server
        InputStreamReader streamReader = null;
        try {
            streamReader = new InputStreamReader(s.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        BufferedReader reader = new BufferedReader(streamReader);

        //Get the response message and print it to console
        String responseMessage = readMessage(reader);
        System.out.println(responseMessage);
        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        writer.close();
    }

    private static String readMessage(BufferedReader IN) {
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while (!(line = IN.readLine()).isEmpty()) {
                System.out.printf("INFO: Read line from input: %n%n\"%s\"%n%n", line);
                sb.append(line).append(System.lineSeparator());
            }
        } catch (IOException e) {
            System.out.printf("NOTICE: Failed to read line from input stream. %s%n", e.getMessage());
        }
        return sb.toString().trim();
    }
}
