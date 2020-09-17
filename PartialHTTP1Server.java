import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
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
        NOT_MODIFIED(304, "NOT MODIFIED"),
        BAD_REQUEST(400, "BAD REQUEST"),
        FORBIDDEN(403, "FORBIDDEN"),
        NOT_FOUND(404, "NOT FOUND"),
        REQUEST_TIMEOUT(408, "REQUEST TIMEOUT"),
        INTERNAL_SERVER_ERROR(500, "INTERNAL SERVER ERROR"),
        NOT_IMPLEMENTED(501, "NOT IMPLEMENTED"),
        SERVICE_UNAVAILABLE(503, "SERVICE UNAVAILABLE"),
        HTTP_VERSION_NOT_SUPPORTED(505, "HTTP VERSION NOT SUPPORTED");

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

        private final String SUPPORTED_VERSION = "HTML/1.0";

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
            String message = this.readMessage();
            
            String[] lines = message.split(System.lineSeparator());
            String[] firstTokens = lines[0].split("\\s+");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
            */
            if(firstTokens.length != 3) {
                this.writeMessage(String.format("%s %s", SUPPORTED_VERSION, Response.BAD_REQUEST));
                this.close();
                return;
            }

            String command = firstTokens[0];
            String resource = firstTokens[1];
            String version = firstTokens[2];

            /*
                If the HTML version is not 1.0, then it's not supported
            */
            if(!version.equals(SUPPORTED_VERSION)) {
                this.writeMessage(String.format("%s %s", SUPPORTED_VERSION, Response.HTTP_VERSION_NOT_SUPPORTED));
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
            if(lines.length > 1 && !lines[1].isEmpty()) {
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
                case "plain":
                    type = "text";
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
            StringBuilder message = new StringBuilder();
            switch(command) {
                case "PUT":
                case "DELETE":
                case "LINK":
                case "UNLINK":
                    return String.format("%s %s", SUPPORTED_VERSION, Response.NOT_IMPLEMENTED);
                //TODO: Build message based off command and resource and second line
                case "GET":
                case "POST":

                    break;
                case "HEAD":

                    break;
                default:
                    return String.format("%s %s", SUPPORTED_VERSION, Response.BAD_REQUEST);

            }
            return message.toString();
        }

        private void writeMessage(String message) {
            System.out.printf("INFO: Wrote line \"%s\" to output.%n", message);
            OUT.println(message);
        }

        private String readMessage() {
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while (!(line = IN.readLine()).isEmpty()) {
                    System.out.printf("INFO: Read line \"%s\" from input.%n", line);
                    sb.append(line);
                }
            } catch (IOException e) {
                System.out.printf("NOTICE: Failed to read line from input stream.%n");
            }
            return sb.toString();
        }

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
        ExecutorService threadPoolService = new ThreadPoolExecutor(5, 50, 5000,
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
                //Possibly need to change to singlethreadexecutor to handle timeouts
                //TODO: Fix this to SingleThreadExecutor to support 5 second timeout
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

