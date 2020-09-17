import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final public class PartialHTTP1Server {

    /*
        Enum containing response codes and messages in case they need to be sent as readable strings
    */
    enum Response {
        OK(200, "OK"),
        NOT_MODIFIED(304, "Not Modified"),
        BAD_REQUEST(400, "Bad Request"),
        FORBIDDEN(403, "Forbidden"),
        NOT_FOUND(404, "Not Found"),
        REQUEST_TIMEOUT(408, "Request Timeout"),
        INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
        NOT_IMPLEMENTED(501, "Not Implemented"),
        SERVICE_UNAVAILABLE(503, "Service Unavailable"),
        HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported");

        private int code;
        private String message;

        private Response(int responseCode, String responseMessage) {
            this.code = responseCode;
            this.message = responseMessage;
        }

        public int getCode() {
            return this.code;
        }

        public String getMessage() {
            return this.message;
        }

        @Override
        public String toString() {
            return String.format("%d %s", this.code, this.message);
        }
    }

    final static class ConnectionHandler implements Runnable {

        private final String HTTP_SUPPORTED_VERSION = "HTTP/1.0";

        private Socket SOCKET;
        private BufferedReader IN;
        private PrintWriter OUT;

        public ConnectionHandler(Socket socket) throws InstantiationException {
            this.SOCKET = socket;
            /*
                Create input and output streams for the socket.
            */
            try {
                this.IN = new BufferedReader(new InputStreamReader(this.SOCKET.getInputStream()));
                this.OUT = new PrintWriter(this.SOCKET.getOutputStream());

            /*
                Error: Failed to create input or output stream for the socket
                Resolution: Inform the user, close any connections that may have been successful, and then throw an exception
            */
            } catch (IOException e) {
                System.out.printf("ERROR: Failed to create IO streams for socket %s.%n%s", this.SOCKET.toString(), e.toString());
                this.close();
                throw new InstantiationException("Failed to create IO streams.");
            }
        }

        @Override
        public void run() {
            //TODO: Add 5 second timeout
            String message = this.readMessage();
            
            String[] lines = message.split(System.lineSeparator());
            String[] firstTokens = lines[0].split("\\s+");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
            */
            if(firstTokens.length != 3) {
                this.writeMessage(String.format("%s %s", HTTP_SUPPORTED_VERSION, Response.BAD_REQUEST));
                this.close();
                return;
            }

            String command = firstTokens[0];
            String resource = firstTokens[1];
            String version = firstTokens[2];

            /*
                If the HTML version is not 1.0, then it's not supported
            */
            if(!version.equals(HTTP_SUPPORTED_VERSION)) {
                this.writeMessage(String.format("%s %s", HTTP_SUPPORTED_VERSION, Response.HTTP_VERSION_NOT_SUPPORTED));
                this.close();
                return;
            }

            /*
                Here we get the appropriate response from our responses hashmap and pass it along with the resource
                and second line (which contains filtering information) to the message building method. If the command
                is not found in the responses, it returns null and in build message this translates to a bad request.
                Otherwise, the message builds the message based off of the response.
            */
            String line2 = null;
            if(lines.length > 1) {
                line2 = lines[1];
            }
            this.writeMessage(this.buildMessage(command, resource, line2));
            this.close();
        }

        private String getMimeType(String extension) {
            extension = extension.toLowerCase();
            String type = "";
            switch(extension) {
                case "html":
                    type = "text";
                    break;
                case "txt":
                    type = "text";
                    extension = "plain";
                    break;
                case "gif":
                case "jpeg":
                case "png":
                    type = "image";
                    break;
                default:
                    type = "application";
                    extension = "octet-stream";
                    break;
            }
            return String.format("%s/%s", type, extension);
        }

        private String buildMessage(String command, String resource, String line2) {
            System.out.printf("INFO: Building message for command %s and resource %s.%n", command, resource);
            StringBuilder message = new StringBuilder();
            switch(command.trim()) {
                case "PUT":
                case "DELETE":
                case "LINK":
                case "UNLINK":
                    message.append(String.format("%s %s", HTTP_SUPPORTED_VERSION, Response.NOT_IMPLEMENTED));
                    break;
                //TODO: Build message based off command and resource and second line
                case "GET":
                case "POST":
                    String header = buildHeader(command, resource, line2);
                    message.append(header);
                    //Future implementations go here
                    break;
                case "HEAD":
                    message.append(buildHeader(command, resource, line2));
                    break;
                default:
                    message.append(String.format("%s %s", HTTP_SUPPORTED_VERSION, Response.BAD_REQUEST));
            }
            System.out.printf("INFO: Built message%n%n\"%s\"%n%n", message.toString());
            return message.toString();
        }
        /*
            Builds the HTTP header for a given command, resource, and second line argument
         */
        private String buildHeader(String command, String resource, String line2) {
            StringBuilder header = new StringBuilder();
            File file = new File(resource);
            if(!file.exists()) {
                return String.format("%s %s", HTTP_SUPPORTED_VERSION, Response.NOT_FOUND);
            }

            SimpleDateFormat sdf = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss z");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

            Set<String> supportedQueries = new HashSet<>() {
                {
                    add("If-Modified-Since");
                }
            };


            return header.toString();
        }

        /*
            Write a message to the output stream. Does not modify the message at all.
         */
        private void writeMessage(String message) {
            System.out.printf("INFO: Wrote line to output: %n%n\"%s\"%n%n", message);
            OUT.println(message);
        }

        /*
            Read a message from the input stream. Pieces it together line by line and trims any trailing
            white space that may mess with String.split()
         */
        private String readMessage() {
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

        /*
            Tries to close any open connections.
         */
        private void close() {
            try {
                // "Once your response has been sent, you should flush() your output streams, wait a quarter second, 
                // close down all communication objects and cleanly exit the communication Thread"
                System.out.printf("INFO: Closing socket, input, and output.%n");
                this.SOCKET.close();
                this.IN.close();
                this.OUT.close();
                Thread.sleep(250); //
            /*
                Error: Failed to close a connection
                Resolution: Warn user and move on.
            */
            } catch (Exception e) {
                System.out.printf("ERROR: Failed to clean up connections for socket %s.%n%s", SOCKET.toString(), e.toString());
            }
        }

    }

    public static void main(final String[] args) {
        /*
            Error: No arguments
            Resolution: Print usage and end
        */
        if (args.length < 1) {
            System.out.println("Usage: java PartialHTTP1Server <port name>");
            return;
        }
        int PORT_NUMBER;
        try {
            PORT_NUMBER = Integer.parseInt(args[0]);

        /*
            Error: Invalid argument
            Resolution: Inform user and end
        */
        } catch (final NumberFormatException e) {
            System.out.println("ERROR: Failed to parse argument to integer.");
            return;
        }

        ServerSocket SERVER_SOCKET;
        try {
            SERVER_SOCKET = new ServerSocket(PORT_NUMBER);

        /*
            Error: Failed to create server socket.
            Resolution: Inform user and end.
        */
        } catch (final Exception e) {
            System.out.printf("ERROR: Failed to create server socket on port %d.%n%s%n", PORT_NUMBER, e.toString());
            return;
        }

        /*
            Create the thread pool. Five arguments
                1. Core Pool Size - We want to keep 5 threads alive when idle
                2. Max Pool Size - We want a maximum of 50 threads running at any time
                3. Time to keep alive - Threads will begin to be killed off after this amount of time of being idle.
                4. Unit of time for #3 - Milliseconds or seconds, don't matter
                5. The Work Queue - We just need any blocking queue so we'll use a linked queue
        */
        ExecutorService threadPoolService = new ThreadPoolExecutor(5, 50, 1000,
                                                                    TimeUnit.MILLISECONDS,
                                                                        new LinkedBlockingQueue<Runnable>());

        Socket SOCKET;
        //Server loop
        while(true) {
            try {
                SOCKET = SERVER_SOCKET.accept();

            /*
                Error: Failed to accept connection on the socket.
                Resolution: Inform the user and move on.
            */
            } catch (final Exception e) {
                System.out.printf("ERROR: Failed to accept connection on socket.%n%s", e.toString());
                continue;
            }

            try {
                threadPoolService.execute(new ConnectionHandler(SOCKET));

            /*
                Error: Thread Pool rejected a new command -indicates maximum connections reached
                Resolution: Write 503 to socket
            */
            } catch (final RejectedExecutionException e) {
                try {
                    //Write error 503 to the socket's output stream
                    PrintWriter OUT = new PrintWriter(SOCKET.getOutputStream(), true);
                    OUT.println(Response.SERVICE_UNAVAILABLE);
                    //Clean up connections
                    OUT.close();
                    SOCKET.close();

                    /*
                        Error: Failed to write to socket and clean up
                        Resolution: Inform user and move on
                    */
                } catch (final IOException ex) {
                    System.out.printf("ERROR: Failed to write 503 response from main.%n%s", ex.toString());
                } catch (final Exception ex) {
                    System.out.printf("ERROR: Unspecified exception.%n%s", ex.toString());
                }
            /*
                Error: Failed to create Client Handler for a connection
                Resolution: Inform the user and move on
            */
            } catch (final InstantiationException e) {
                System.out.printf("ERROR: Failed to create thread for socket %s.%n%s", SOCKET.toString(), e.toString());
            }
        }  
    }
}

