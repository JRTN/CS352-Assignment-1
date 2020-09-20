import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
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

        //Currently only support HTTP 1.0
        private final String HTTP_SUPPORTED_VERSION = "HTTP/1.0";
        //Form of: Sun, 20 Sep 2020 04:49:17 GMT
        private final String HTTP_DATETIME_FORMAT = "E, d MMM yyyy HH:mm:ss z";
        //The time we want to wait for a request
        private final int REQUEST_TIMEOUT_MS = 5000;

        private Socket SOCKET;
        private BufferedReader IN;
        private BufferedWriter OUT;

        public ConnectionHandler(Socket socket) throws InstantiationException {
            this.SOCKET = socket;
            /*
                Create input and output streams for the socket.
            */
            try {
                this.IN = new BufferedReader(new InputStreamReader(this.SOCKET.getInputStream()));
                this.OUT = new BufferedWriter(new OutputStreamWriter(this.SOCKET.getOutputStream()));

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
            String message = this.readMessage(REQUEST_TIMEOUT_MS);

            if (message == null) {
                this.writeMessage(buildStatusLine(Response.REQUEST_TIMEOUT));
                this.close();
                return;
            }

            String[] lines = message.split("\r\n");
            String[] requestTokens = lines[0].split(" ");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
            */
            if (requestTokens.length != 3) {
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
            if (!version.equals(HTTP_SUPPORTED_VERSION)) {
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
            if (lines.length > 1) {
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
            switch (extension) {
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

        /*
            Builds a response message given a request
         */
        private String buildMessage(String command, String resource, String headerLines) {
            System.out.printf("INFO: Building message for command %s and resource %s.%n", command, resource);
            StringBuilder message = new StringBuilder();
            switch (command.trim()) {
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
            Formats a status line for a given response. The format of a status line is
                HTTP/<ver> <code> <desc>\r\n
         */
        private String buildStatusLine(Response response) {
            return String.format("%s %d %s\r\n", HTTP_SUPPORTED_VERSION, response.getCode(), response.getMessage());
        }

        /*
            Formats a header line with the given field name and value. The format of a header line is
                <field>: <value>\r\n
         */
        private String buildHeaderLine(String fieldName, String value) {
            return String.format("%s: %s\r\n", fieldName, value);
        }

        /*
            Builds the HTTP header for a given command, resource, and second line argument. The format of a header is
                <status line>
                <header_line1>
                <header_line2>
                    ...
                <header_linen>
                \r\n
                <payload>
         */
        private String buildHeader(String command, String resource, String requestFields) {
            StringBuilder responseHeader = new StringBuilder();

            //Date formatter in GMT
            SimpleDateFormat sdf = new SimpleDateFormat(this.HTTP_DATETIME_FORMAT);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

            Path path = Paths.get("." + resource);
            File file = path.toFile();

            System.out.printf("INFO: Opening object %s for response.%n", path.toString());

            /* If file does not exist, we respond with not found */
            if (!file.exists()) {
                responseHeader.append(buildStatusLine(Response.NOT_FOUND));
                return responseHeader.toString();
            }
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

            //If we can't access the file, then it's response code forbidden
            if (!file.canRead()) {
                responseHeader.append(buildStatusLine(Response.FORBIDDEN));
                return responseHeader.toString();
            }

            String conditional = "If-Modified-Since: ";
            if (requestFields != null && requestFields.contains(conditional)) {
                String conditionalDateString = requestFields.substring(conditional.length() + 1);
                Date conditionalDate;
                try {
                    conditionalDate = sdf.parse(conditionalDateString);
                        /*
                            Error: We can't parse the conditional date
                            Resolution: Send response bad request
                         */
                } catch (ParseException e) {
                    responseHeader.append(buildStatusLine(Response.BAD_REQUEST));
                    return responseHeader.toString();
                }

                    /*
                        If the requested modified date is after when it was modified, then we send not modified
                     */
                if (conditionalDate.after(lastModified)) {
                    responseHeader.append(buildStatusLine(Response.NOT_MODIFIED));
                    return responseHeader.toString();
                }
            }

            byte[] contents;
            try {
                contents = Files.readAllBytes(path);
                    /*
                        Error: Failed to read contents of the file
                        Resolution: Send response internal server error
                     */
            } catch (IOException e) {
                responseHeader.append(buildStatusLine(Response.INTERNAL_SERVER_ERROR));
                return responseHeader.toString();
            }

            //Build the header with its status line and header lines
            responseHeader.append(buildStatusLine(Response.OK));
            responseHeader.append(buildHeaderLine("Content-Type", contentType));
            responseHeader.append(buildHeaderLine("Content-Length", Long.toString(contentLength)));
            responseHeader.append(buildHeaderLine("Last-Modified", sdf.format(lastModified)));
            responseHeader.append(buildHeaderLine("Content-Encoding", contentEncoding));
            responseHeader.append(buildHeaderLine("Allow", "GET, POST, HEAD"));
            responseHeader.append(buildHeaderLine("Expires", sdf.format(expires)));
            responseHeader.append("\r\n");
            responseHeader.append(new String(contents));
            responseHeader.append("\r\n");


            return responseHeader.toString();
        }

        /*
            Write a message to the output stream. Does not modify the message at all.
         */
        private void writeMessage(String message) {
            System.out.printf("INFO: Wrote line to output: %n%n\"%s\"%n%n", message);
            try {
                this.OUT.write(message);
                this.OUT.flush();
            } catch (IOException e) {
                System.out.printf("INFO: Failed to write to output stream.%n");
            }
        }

        /*
            Read a message from the input stream. Pieces it together line by line and trims any trailing
            white space that may mess with String.split()
         */
        private String readMessage(int timeoutMS) {
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            long end = start + timeoutMS;
            try {
                //Continually check if 5 seconds has passed while the buffered reader does not have any messages
                while (!this.IN.ready()) {
                    if (System.currentTimeMillis() > end) {
                        return null;
                    }
                }
                while (!(line = this.IN.readLine()).isEmpty()) {
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
        while (true) {
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
                    BufferedWriter OUT = new BufferedWriter(new OutputStreamWriter(SOCKET.getOutputStream()));
                    OUT.write(String.format("HTTP/1.0 %s\r\n", Response.SERVICE_UNAVAILABLE));
                    //Clean up connections
                    OUT.flush();
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
