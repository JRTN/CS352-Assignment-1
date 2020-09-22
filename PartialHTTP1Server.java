import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
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
    enum StatusCode {
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

        private final int code;
        private final String message;

        StatusCode(int statusCode, String responseMessage) {
            code = statusCode;
            message = responseMessage;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return String.format("%d %s", code, message);
        }
    }

    final static class ConnectionHandler implements Runnable {

        //Currently only support HTTP 1.0
        private final String HTTP_SUPPORTED_VERSION = "HTTP/1.0";

        private final Socket SOCKET;
        private final BufferedReader IN;
        private final BufferedOutputStream OUT;
        private final SimpleDateFormat dateFormatter;


        public ConnectionHandler(Socket socket) throws InstantiationException {
            SOCKET = socket;
            /*
                Create input and output streams for the socket.
            */
            try {
                IN = new BufferedReader(new InputStreamReader(SOCKET.getInputStream()));
                OUT = new BufferedOutputStream(SOCKET.getOutputStream());

            /*
                Error: Failed to create input or output stream for the socket
                Resolution: Inform the user, close any connections that may have been successful, and then throw an exception
            */
            } catch (IOException e) {
                System.out.printf("ERROR: Failed to create IO streams for socket %s.%n%s", SOCKET.toString(), e.toString());
                close();
                throw new InstantiationException("Failed to create IO streams.");
            }
            //Form of: Sun, 20 Sep 2020 04:49:17 GMT
            String HTTP_DATETIME_FORMAT = "E, dd MMM yyyy HH:mm:ss z";
            dateFormatter = new SimpleDateFormat(HTTP_DATETIME_FORMAT);
            String HTTP_DATETIME_ZONE = "GMT";
            dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATETIME_ZONE));
        }

        @Override
        public void run() {
            //The time we want to wait for a request
            int REQUEST_TIMEOUT_MS = 5000;
            String message = receive(REQUEST_TIMEOUT_MS);

            if (message == null) {
                send(buildStatusLine(StatusCode.REQUEST_TIMEOUT));
                close();
                return;
            }

            String[] lines = message.split("\r\n");
            String[] requestTokens = lines[0].split(" ");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
            */
            if (requestTokens.length != 3) {
                send(buildStatusLine(StatusCode.BAD_REQUEST));
                close();
                return;
            }

            String command = requestTokens[0];
            String resource = requestTokens[1];
            String version = requestTokens[2];

            /*
                If the HTML version is not 1.0, then it's not supported
            */
            if (!version.equals(HTTP_SUPPORTED_VERSION)) {
                send(buildStatusLine(StatusCode.HTTP_VERSION_NOT_SUPPORTED));
                close();
                return;
            }

            String headerLines = null;
            if (lines.length > 1) {
                headerLines = lines[1];
            }
            sendResponse(command, resource, headerLines);
            close();
        }

        /*
            Gets the proper MIME string according to file extension. This is in the form of
                <type>/<subtype>
            Subtype is usually the extension, but sometimes differs.
         */
        private String getMimeType(String extension) {
            extension = extension.toLowerCase();
            String type;
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
                case "pdf":
                    type = "application";
                    break;
                default:
                    type = "application";
                    extension = "octet-stream";
                    break;
            }
            return String.format("%s/%s", type, extension);
        }

        private String getConditionalDateString(String headerLines) {
            if(headerLines == null) {
                return null;
            }
            return headerLines.substring("If-Modified-Since: ".length());
        }

        private Date parseDate(String dateString) {
            try {
                return dateFormatter.parse(dateString);
            } catch (ParseException e) {
                return new Date(0);
            }
        }

        private String formatDate(Date date) {
            return dateFormatter.format(date);
        }

        /*
            Builds a response message given a request
         */
        private void sendResponse(String command, String resource, String headerLines) {
            System.out.printf("INFO: Building message for command %s and resource %s.%n", command, resource);
            File file = getResource(resource);
            if(!file.exists()) {
                send(buildStatusLine(StatusCode.NOT_FOUND));
                return;
            }
            switch (command.trim()) {
                case "PUT":
                case "DELETE":
                case "LINK":
                case "UNLINK":
                    send(buildStatusLine(StatusCode.NOT_IMPLEMENTED));
                    return;
                case "GET":
                case "POST":
                case "HEAD":
                    StringBuilder message = new StringBuilder();
                    message.append(buildHeader(file, getConditionalDateString(headerLines)));

                    if(command.equals("HEAD")) {
                        send(message.toString());
                        return;
                    }

                    byte[] payload;
                    try {
                        payload = buildPayload(file);
                    } catch (AccessDeniedException e) {
                        send(buildStatusLine(StatusCode.FORBIDDEN));
                        return;
                    } catch (IOException e) {
                        send(buildStatusLine(StatusCode.INTERNAL_SERVER_ERROR));
                        return;
                    }

                    send(message.toString());
                    send(payload);
                    return;
                default:
                    send(buildStatusLine(StatusCode.BAD_REQUEST));
            }
        }

        /*
            Formats a status line for a given response. The format of a status line is
                HTTP/<ver> <code> <desc>\r\n
         */
        private String buildStatusLine(StatusCode statusCode) {
            return String.format("%s %d %s\r\n", HTTP_SUPPORTED_VERSION, statusCode.getCode(), statusCode.getMessage());
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
            This method assumes the file DOES EXIST
         */

        private String buildHeader(File file, String conditionalDateString) {
            String filePath = file.getPath();
            String extension = filePath.substring(filePath.lastIndexOf('.') + 1);
            String contentType = getMimeType(extension);
            long contentLength = file.length();
            Date lastModified = new Date(file.lastModified());
            String contentEncoding = "identity";

            if(conditionalDateString != null) {
                Date conditionalDate = parseDate(conditionalDateString);

                if (lastModified.before(conditionalDate)) {
                    return buildStatusLine(StatusCode.NOT_MODIFIED);
                }
            }

            Calendar c = Calendar.getInstance();
            c.setTime(new Date());
            c.add(Calendar.HOUR, 24);
            Date expires = c.getTime();

            //Build the header with its status line and header lines

            return buildStatusLine(StatusCode.OK) +
                    buildHeaderLine("Content-Type", contentType) +
                    buildHeaderLine("Content-Length", Long.toString(contentLength)) +
                    buildHeaderLine("Last-Modified", formatDate(lastModified)) +
                    buildHeaderLine("Content-Encoding", contentEncoding) +
                    buildHeaderLine("Allow", "GET, POST, HEAD") +
                    buildHeaderLine("Expires", formatDate(expires)) +
                    "\r\n";
        }

        private byte[] buildPayload(File resource) throws IOException {
            return Files.readAllBytes(resource.toPath());
        }

        private File getResource(String resource) {
            Path path = Paths.get("." + resource);
            return path.toFile();
        }

        private void send(String str) {
            send(str.getBytes());
        }

        private void send(byte[] bytes) {
            try {
                OUT.write(bytes);
                OUT.flush();
            } catch (IOException e) {
                System.out.printf("ERROR: Failed to write to write to output stream: %s%n", e.getMessage());
            }
        }

        /*
            Read a message from the input stream. Pieces it together line by line and trims any trailing
            white space that may mess with String.split()
         */
        private String receive(int timeoutMS) {
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            long end = start + timeoutMS;
            try {
                //Continually check if 5 seconds has passed while the buffered reader does not have any messages
                while (!IN.ready()) {
                    if (System.currentTimeMillis() > end) {
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
                if(SOCKET != null) SOCKET.close();
                if(IN != null) IN.close();
                if(OUT != null) OUT.close();
                Thread.sleep(250); //
            /*
                Error: Failed to close a connection
                Resolution: Warn user and move on.
            */
            } catch (Exception e) {
                System.out.printf("ERROR: Failed to clean up connections for socket.%n%s", e.toString());
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
                new LinkedBlockingQueue<>());

        Socket SOCKET;
        //Server loop
        //noinspection InfiniteLoopStatement
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
                    OUT.write(String.format("HTTP/1.0 %s\r\n", StatusCode.SERVICE_UNAVAILABLE));
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
