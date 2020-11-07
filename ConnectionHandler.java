import java.io.*;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.TimeZone;

final public class ConnectionHandler implements Runnable {

    // Currently only support HTTP 1.0
    private final String HTTP_SUPPORTED_VERSION = "HTTP/1.0";

    private final Socket SOCKET;
    private final BufferedInputStream IN;
    private final BufferedOutputStream OUT;
    private final SimpleDateFormat dateFormatter;

    public ConnectionHandler(Socket socket) throws InstantiationException {
        SOCKET = socket;
        /*
         * Create input and output streams for the socket.
         */
        try {
            IN = new BufferedInputStream(SOCKET.getInputStream());
            OUT = new BufferedOutputStream(SOCKET.getOutputStream());

            /*
             * Error: Failed to create input or output stream for the socket Resolution:
             * Inform the user, close any connections that may have been successful, and
             * then throw an exception
             */
        } catch (IOException e) {
            System.out.printf("ERROR: Failed to create IO streams for socket %s.%n%s", SOCKET.toString(), e.toString());
            close();
            throw new InstantiationException("Failed to create IO streams.");
        }
        // Form of: Sun, 20 Sep 2020 04:49:17 GMT
        String HTTP_DATETIME_FORMAT = "E, dd MMM yyyy HH:mm:ss z";
        dateFormatter = new SimpleDateFormat(HTTP_DATETIME_FORMAT);
        String HTTP_DATETIME_ZONE = "GMT";
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATETIME_ZONE));
    }

    @Override
    public void run() {
        // The time we want to wait for a request
        int REQUEST_TIMEOUT_MS = 5000;
        String message;
        try {
            message = receive(REQUEST_TIMEOUT_MS);
            System.out.println("Received message:\n" + message);
        } catch (IOException e) {
            System.out.printf("ERROR: Failed to read input from socket. %s%n", e.getMessage());
            send(buildStatusLine(Types.StatusCode.INTERNAL_SERVER_ERROR));
            close();
            return;
        }

        if (message == null) {
            send(buildStatusLine(Types.StatusCode.REQUEST_TIMEOUT));
            close();
            return;
        }

        String[] lines = message.split("\r\n");

        sendResponse(lines);
        close();
    }

    /*
     * Finds the conditional string in the request header lines and extracts it.
     * Currently only expects one line, but can be expanded if later more
     * complicated headers are given
     */
    private String getConditionalDateString(String headerLines) {
        if (headerLines == null || headerLines.startsWith("If-Modified-Since: ")) {
            return null;
        }
        return headerLines.substring("If-Modified-Since: ".length());
    }

    /*
     * Attempts to parse a date from a string using our SimpleDateFormatter. If the
     * date we try to parse is invalid, then it will be set to the first Date
     * available. Meaning, it is always before all other dates.
     */
    private Date parseDate(String dateString) {
        try {
            return dateFormatter.parse(dateString);
        } catch (ParseException e) {
            return new Date(0);
        }
    }

    /*
     * Formats a Date using our SimpleDateFormatter
     */
    private String formatDate(Date date) {
        return dateFormatter.format(date);
    }

    /*
     * Passes appropriate information to the build methods and then collects the
     * output. Compiles the message and sends it via the output methods.
     */
    private void sendResponse(String[] lines) {
        System.out.printf("Sending Response for lines: %s\n", Arrays.toString(lines));
        if (lines.length < 1) {
            send(buildStatusLine(Types.StatusCode.BAD_REQUEST));
        }

        String[] requestTokens = lines[0].split(" ");

        /*
         * If there aren't 3 tokens in the first line, it's a bad request
         */
        if (requestTokens.length != 3) {
            send(buildStatusLine(Types.StatusCode.BAD_REQUEST));
            close();
            return;
        }

        String command = requestTokens[0];
        String resource = requestTokens[1];
        String version = requestTokens[2];

        /*
         * If the HTML version is not 1.0, then it's not supported
         */
        if (!version.equals(HTTP_SUPPORTED_VERSION)) {
            send(buildStatusLine(Types.StatusCode.HTTP_VERSION_NOT_SUPPORTED));
            close();
            return;
        }

        System.out.printf("INFO: Building message for command %s and resource %s.%n", command, resource);
        switch (command.trim()) {
            // UNIMPLEMENTED COMMANDS
            case "PUT":
            case "DELETE":
            case "LINK":
            case "UNLINK":
                send(buildStatusLine(Types.StatusCode.NOT_IMPLEMENTED));
                return;
            // IMPLEMENTED COMMANDS
            case "POST":
                // TODO: Implement POST

                String from = null;
                String userAgent = null;
                String contentType = null;
                int contentLength = -1;
                String argumentString = null;

                // Extract information from lines
                for (String line : lines) {
                    line = line.trim(); // Get rid of the trailing \r\n
                    if (line.startsWith("From: ")) {
                        from = line.substring("From :".length());
                    } else if (line.startsWith("User-Agent: ")) {
                        userAgent = line.substring("User-Agent: ".length());
                    } else if (line.startsWith("Content-Length: ")) {
                        String value = line.substring("Content-Length: ".length());
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            send(buildStatusLine(Types.StatusCode.LENGTH_REQUIRED));
                        }
                    } else if (line.startsWith("Content-Type: ")) {
                        contentType = line.substring("Content-Type: ".length());
                    } else if (!line.isEmpty()) { // Payload line
                        argumentString = new ArgumentDecoder(line).toString();
                    }
                }

                System.out.println("Extracted information for post request: ");
                System.out.println("\tFrom: " + from);
                System.out.println("\tUser Agent: " + userAgent);
                System.out.println("\tContent Type: " + contentType);
                System.out.println("\tContent Length: " + contentLength);
                System.out.println("\tArguments: " + argumentString);
                

                // If the lines don't include content length
                if (contentLength < 0) {
                    send(buildStatusLine(Types.StatusCode.LENGTH_REQUIRED));
                    return;
                }
                // If the lines don't include content type
                if (contentType == null) {
                    send(buildStatusLine(Types.StatusCode.INTERNAL_SERVER_ERROR));
                    return;
                }
                // If the requested resource is not a cgi script
                if (!resource.endsWith(".cgi")) {
                    send(buildStatusLine(Types.StatusCode.METHOD_NOT_ALLOWED));
                }

                ProcessBuilder builder = new ProcessBuilder("." + resource);
                Map<String, String> environment = builder.environment();
                environment.put("CONTENT_LENGTH", "" + contentLength);
                environment.put("SCRIPT_NAME", resource);
                environment.put("SERVER_NAME", "localhost");
                environment.put("PORT", "" + SOCKET.getPort());
                if (from != null) {
                    environment.put("HTTP_FROM", from);
                }
                if (userAgent != null) {
                    environment.put("HTTP_USER_AGENT", userAgent);
                }

                Process process;
                try {
                    process = builder.start();

                } catch (IOException e1) {
                    System.out.printf("INFO: Failed to run process and capture output: %s\n", e1.getMessage());
                    send(buildStatusLine(Types.StatusCode.FORBIDDEN));
                    return;
                }

                BufferedReader process_output = new BufferedReader(new InputStreamReader(process.getInputStream()));

                try {
                    process.getOutputStream().write(argumentString.getBytes());
                } catch (IOException e2) {
                    System.out.printf("INFO: Failed to write output to process %s: %s\n", resource, e2.getMessage());
                }

                StringBuilder output = new StringBuilder();
                try {
                    String line;
                    while((line = process_output.readLine()) != null) {
                        output.append(line);
                    }
                    process_output.close();
                    System.out.printf("INFO: Read input from process %s: %s\n", resource, output.toString());
                } catch (IOException e1) {
                    System.out.printf("INFO: Failed to read input from process: %s\n", e1.getMessage());
                }
                

                return;
            case "GET":
            case "HEAD":
                File file = getResource(resource);
                if (!file.exists()) {
                    send(buildStatusLine(Types.StatusCode.NOT_FOUND));
                    return;
                }

                if(file.isDirectory()) {
                    send(buildStatusLine(Types.StatusCode.FORBIDDEN));
                    return;
                }

                if (command.equals("HEAD")) {
                    send(buildHeadGetHeader(file, null));
                    return;
                }

                byte[] payload;
                try {
                    payload = buildPayload(file);
                    //An AccessDeniedException indicates we need to write FORBIDDEN as the file is not
                    //able to be opened by the user
                } catch (AccessDeniedException e) {
                    send(buildStatusLine(Types.StatusCode.FORBIDDEN));
                    return;
                    //General catch-all for the rest of the exceptions. Indicates something non permission
                    //related went wrong and we need to send INTERNAL_SERVER_ERROR
                } catch (IOException e) {
                    send(buildStatusLine(Types.StatusCode.INTERNAL_SERVER_ERROR));
                    return;
                }

                String conditionalLine = null;

                if(lines.length > 1) {
                    conditionalLine = lines[1];
                }

                //For GET and POST, send both the header and payload
                send(buildHeadGetHeader(file, getConditionalDateString(conditionalLine)));
                send(payload);
                return;
            default:
                //Commands that are not recognized are bad requests
                send(buildStatusLine(Types.StatusCode.BAD_REQUEST));
        }
    }

    /*
        Formats a status line for a given response. The format of a status line is
            HTTP/<ver> <code> <desc>\r\n
     */
    private String buildStatusLine(Types.StatusCode statusCode) {
        return String.format("%s %d %s\r\n", HTTP_SUPPORTED_VERSION, statusCode.getCode(), statusCode.getMessage());
    }

    private String buildPostHeader() {

        return "";
    }

    /*
        Builds and formats a header for a given resource and header lines. Currently only
        expects one line (the conditional header line) as a second argument.
     */
    private String buildHeadGetHeader(File file, String conditionalDateString) {
        //If there is a conditional String, then we need to check the last modified date
        //of the file against the current date
        if (conditionalDateString != null) {
            Date conditionalDate = parseDate(conditionalDateString);
            Date lastModified = new Date(file.lastModified());

            //We send NOT_MODIFIED if the file was last modified before the conditional date
            if (lastModified.before(conditionalDate)) {
                return buildStatusLine(Types.StatusCode.NOT_MODIFIED) +
                        buildHeaderLine(Types.HeaderField.Expires, file);
            }
        }

        //Call builder methods with the appropriate fields and OK status as all other checks
        //have passed and this is a valid request
        return buildStatusLine(Types.StatusCode.OK) +
                buildHeaderLine(Types.HeaderField.ContentType, file) +
                buildHeaderLine(Types.HeaderField.ContentLength, file) +
                buildHeaderLine(Types.HeaderField.LastModified, file) +
                buildHeaderLine(Types.HeaderField.ContentEncoding, file) +
                buildHeaderLine(Types.HeaderField.Allow, file) +
                buildHeaderLine(Types.HeaderField.Expires, file) +
                "\r\n";
    }


    /*
        Builds and formats a header line for a given header field and file.
        A header line is of the format
            <field>: <value>\r\n
        Where the field is defined in Types.Headerfield and the value is determined
        from the file. We can assume the file exists because we only pass this method
        a valid file
     */
    public String buildHeaderLine(Types.HeaderField field, File file) {
        String headerLine = field.toString() + ": %s\r\n";
        String value;
        switch (field) {
            case ContentType:
                String filePath = file.getPath();
                //The file extension is the substring starting at the last location of the
                //'.' character. Note that if there is no dot character (and thus no extension)
                //the substring equals the file name.
                String extension = filePath.substring(filePath.lastIndexOf('.') + 1);
                //If the extension equals the filename, then it has no extension.
                if (extension.equals(file.getName())) {
                    extension = "";
                }
                Types.MIME mime = Types.MIME.get(extension);
                value = mime.toString();
                //value = URLConnection.getFileNameMap().getContentTypeFor(file.getName()) //Possibly use this in the future. Need to check that extension != ""
                break;

            case ContentLength:
                long contentLength = file.length();
                value = Long.toString(contentLength);
                break;

            case LastModified:
                Date lastModified = new Date(file.lastModified());
                value = formatDate(lastModified);
                break;

            case ContentEncoding:
                //Currently we do not encode content, so the value is identity
                value = "identity";
                break;

            case Allow:
                //Return the allowed HTTP request types
                value = "GET, HEAD, POST";
                break;

            case Expires:
                //Currently we set packets to expire in 1 year
                Calendar c = Calendar.getInstance();
                c.setTime(new Date());
                c.add(Calendar.YEAR, 1);
                Date expires = c.getTime();
                value = formatDate(expires);
                break;

            default:
                return "";
        }
        return String.format(headerLine, value);
    }

    /*  Gets the contents of a file as bytes. Throws IOException if there
        is an error opening the file
    */
    private byte[] buildPayload(File resource) throws IOException {
        return Files.readAllBytes(resource.toPath());
    }

    /*
        Converts the requested resource to a relative path by prepending '.' and returns
        the file found at the path which may or may not exist.
     */
    private File getResource(String resource) {
        Path path = Paths.get("." + resource);
        return path.toFile();
    }

    /*
        Sends a string through the socket by passing to send(byte[]) the
        bytes of the string
     */
    private void send(String str) {
        System.out.printf("INFO: Sending String to socket %n\"%s\"%n", str.replace("\r\n", "[CRLF]\r\n"));
        send(str.getBytes());
    }

    /*
        Sends a message through the socket. This is achieved using a BufferedOutputStream
     */
    private void send(byte[] bytes) {
        try {
            OUT.write(bytes);
            OUT.flush();
        /*
            Error: Could not send data or flush output channel
            Resolution: Inform the user and move on. We can't send a message to the client
            because something is wrong with output so we don't send INTERNAL_SERVER_ERROR
         */
        } catch (IOException e) {
            System.out.printf("ERROR: Failed to write to write to output stream: %s%n", e.getMessage());
        }
    }

    /*
        Read a message from the input stream. Pieces it together line by line and trims any trailing
        white space that may mess with String.split()
     */
    private String receive(int timeoutMS) throws IOException {
        StringBuilder sb = new StringBuilder();

        while(IN.available() > 0) {
            char c = (char) IN.read();
            sb.append(c);
        }

        return sb.toString();
    }

    /*
        Tries to close any open connections.
     */
    private void close() {
        try {
            // "Once your response has been sent, you should flush() your output streams, wait a quarter second,
            // close down all communication objects and cleanly exit the communication Thread"
            System.out.printf("INFO: Closing socket, input, and output.%n");
            if (OUT != null) OUT.flush();

            Thread.sleep(250);

            if (SOCKET != null) SOCKET.close();
            if (IN != null) IN.close();
            if (OUT != null) OUT.close();
            /*
                Error: Failed to close a connection
                Resolution: Warn user and move on.
            */
        } catch (Exception e) {
            System.out.printf("ERROR: Failed to clean up connections for socket.%n%s", e.getMessage());
        }
    }

}
