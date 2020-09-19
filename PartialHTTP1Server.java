import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
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
        private final int REQUEST_TIMEOUT_MS = 5000;

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

            if(message == null) {
                this.writeMessage(buildStatusLine(Response.REQUEST_TIMEOUT));
                this.close();
                return;
            }
            
            String[] lines = message.split("\r\n");
            String[] requestTokens = lines[0].split(" ");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
            */
            if(requestTokens.length != 3) {
                this.writeMessage(buildStatusLine(Response.BAD_REQUEST));
                this.close();
                return;
            }

            String command = requestTokens[0];
            String resource = requestTokens[1];
            String version = requestTokens[2];

            /*
                If the HTML version is not 1.0, then it's not supported
            */
            if(!version.equals(HTTP_SUPPORTED_VERSION)) {
                this.writeMessage(buildStatusLine(Response.HTTP_VERSION_NOT_SUPPORTED));
                this.close();
                return;
            }

            /*
                Here we get the appropriate response from our responses hashmap and pass it along with the resource
                and second line (which contains filtering information) to the message building method. If the command
                is not found in the responses, it returns null and in build message this translates to a bad request.
                Otherwise, the message builds the message based off of the response.
            */
            String headerLines = null;
            if(lines.length > 1) {
                headerLines = lines[1];
            }
            this.writeMessage(this.buildMessage(command, resource, headerLines));
            this.close();
        }

        /*
            Gets the proper MIME string according to file extension. This is in the form of
                <type>/<subtype>
            Subtype is usually the extension, but sometimes differs.
         */
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

        private String buildMessage(String command, String resource, String headerLines) {
            System.out.printf("INFO: Building message for command %s and resource %s.%n", command, resource);
            StringBuilder message = new StringBuilder();
            switch(command.trim()) {
                case "PUT":
                case "DELETE":
                case "LINK":
                case "UNLINK":
                    message.append(buildStatusLine(Response.NOT_IMPLEMENTED));
                    break;
                case "GET":
                case "POST":
                    String header = buildHeader(command, resource, headerLines);
                    message.append(header);
                    //Future implementations go here
                    break;
                case "HEAD":
                    message.append(buildHeader(command, resource, headerLines));
                    break;
                default:
                    message.append(buildStatusLine(Response.BAD_REQUEST));
            }
            System.out.printf("INFO: Built message%n%n\"%s\"%n%n", message.toString());
            return message.toString();
        }

        /*
            Formats a status line for a given response.
         */
        private String buildStatusLine(Response response) {
            return String.format("%d %s %s\r\n", response.getCode(), response.getMessage(), HTTP_SUPPORTED_VERSION);
        }

        /*
            Formats a header line with the given field name and value.
         */
        private String buildHeaderLine(String fieldName, String value) {
            return String.format("%s: %s\r\n", fieldName, value);
        }

        /*
            Builds the HTTP header for a given command, resource, and second line argument
         */
        private String buildHeader(String command, String resource, String requestFields) {
            StringBuilder responseHeader = new StringBuilder();

            //Date formatter in GMT
            SimpleDateFormat sdf = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss z");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

            //TODO: Add conditional get

            Path path = Paths.get("." + resource);
            File file = path.toFile();

            System.out.printf("INFO: Opening object %s for response.%n", path.toString());

            /* If file does not exist, we send error 404 */
            if(!file.exists()) {
                responseHeader.append(buildStatusLine(Response.NOT_FOUND));
            } else {
                String extension = resource.substring(resource.lastIndexOf('.') + 1);
                String contentType = getMimeType(extension);
                long contentLength = file.length();
                Date lastModified = new Date(file.lastModified());
                String contentEncoding = "identity";

                /* Set expiry date to 24 hours in the future */
                Date expires = new Date();
                Calendar c = Calendar.getInstance();
                c.setTime(expires);
                c.add(Calendar.HOUR, 24);

                if(!file.canRead()) {
                    responseHeader.append(buildStatusLine(Response.FORBIDDEN));
                    responseHeader.append("\r\n");
                    return responseHeader.toString();
                }

                String conditional = "If-Modified-Since: ";
                if(requestFields != null && requestFields.contains(conditional)) {
                    String conditionalDateString = requestFields.substring(conditional.length() + 1);
                    Date conditionalDate;
                    try {
                        conditionalDate = sdf.parse(conditionalDateString);
                    } catch (ParseException e) {
                        responseHeader.append(buildStatusLine(Response.BAD_REQUEST));
                        responseHeader.append("\r\n");
                        return responseHeader.toString();
                    }

                    if(conditionalDate.after(lastModified)) {
                        responseHeader.append(buildStatusLine(Response.NOT_MODIFIED));
                        responseHeader.append("\r\n");
                        return responseHeader.toString();
                    }
                }

                responseHeader.append(buildStatusLine(Response.OK));
                responseHeader.append(buildHeaderLine("Content-Type", contentType));
                responseHeader.append(buildHeaderLine("Content-Length", Long.toString(contentLength)));
                responseHeader.append(buildHeaderLine("Last-Modified", sdf.format(lastModified)));
                responseHeader.append(buildHeaderLine("Content-Encoding", contentEncoding));
                responseHeader.append(buildHeaderLine("Allow", "GET, POST, HEAD"));
                responseHeader.append(buildHeaderLine("Expires", sdf.format(expires)));

            }

            responseHeader.append("\r\n");
            return responseHeader.toString();
        }

        /*
            Write a message to the output stream. Does not modify the message at all.
         */
        //TODO: Fix this so messages are read by tester properly
        private void writeMessage(String message) {
            System.out.printf("INFO: Wrote line to output: %n%n\"%s\"%n%n", message);
            OUT.print(message);
            OUT.flush();
        }

        /*
            Read a message from the input stream. Pieces it together line by line and trims any trailing
            white space that may mess with String.split()
         */
        private String readMessage() {
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            long end = start + this.REQUEST_TIMEOUT_MS;
            try {
                //Continually check if 5 seconds has passed while the buffered reader does not have any messages
                while(!IN.ready()) {
                    if(System.currentTimeMillis() > end) {
                        return null;
                    }
                }
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
                    OUT.printf("HTTP/1.0 %s", Response.SERVICE_UNAVAILABLE);
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

