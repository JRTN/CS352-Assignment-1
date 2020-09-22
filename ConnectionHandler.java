import java.io.*;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class ConnectionHandler implements Runnable {

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
            send(buildStatusLine(Types.StatusCode.REQUEST_TIMEOUT));
            close();
            return;
        }

        String[] lines = message.split("\r\n");
        String[] requestTokens = lines[0].split(" ");

            /*
                If there aren't 3 tokens in the first line, it's a bad request
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
                If the HTML version is not 1.0, then it's not supported
            */
        if (!version.equals(HTTP_SUPPORTED_VERSION)) {
            send(buildStatusLine(Types.StatusCode.HTTP_VERSION_NOT_SUPPORTED));
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
            send(buildStatusLine(Types.StatusCode.NOT_FOUND));
            return;
        }
        switch (command.trim()) {
            case "PUT":
            case "DELETE":
            case "LINK":
            case "UNLINK":
                send(buildStatusLine(Types.StatusCode.NOT_IMPLEMENTED));
                return;
            case "GET":
            case "POST":
            case "HEAD":
                StringBuilder message = new StringBuilder();

                if(command.equals("HEAD")) {
                    message.append(buildHeader(file, getConditionalDateString(null)));
                    send(message.toString());
                    return;
                }

                message.append(buildHeader(file, getConditionalDateString(headerLines)));

                byte[] payload;
                try {
                    payload = buildPayload(file);
                } catch (AccessDeniedException e) {
                    send(buildStatusLine(Types.StatusCode.FORBIDDEN));
                    return;
                } catch (IOException e) {
                    send(buildStatusLine(Types.StatusCode.INTERNAL_SERVER_ERROR));
                    return;
                }

                send(message.toString());
                send(payload);
                return;
            default:
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

    private String buildHeader(File file, String conditionalDateString) {
        if(conditionalDateString != null) {
            Date conditionalDate = parseDate(conditionalDateString);
            Date lastModified = new Date(file.lastModified());

            if(lastModified.before(conditionalDate)) {
                return buildStatusLine(Types.StatusCode.NOT_MODIFIED) +
                        buildHeaderLine(Types.HeaderField.Expires, file);
            }
        }

        return buildStatusLine(Types.StatusCode.OK) +
                buildHeaderLine(Types.HeaderField.ContentType, file) +
                buildHeaderLine(Types.HeaderField.ContentLength, file) +
                buildHeaderLine(Types.HeaderField.LastModified, file) +
                buildHeaderLine(Types.HeaderField.ContentEncoding, file) +
                buildHeaderLine(Types.HeaderField.Allow, file) +
                buildHeaderLine(Types.HeaderField.Expires, file) +
                "\r\n";
    }


    public String buildHeaderLine(Types.HeaderField field, File file) {
        String headerLine = field.toString() + ": %s\r\n";
        String value;
        switch(field) {
            case ContentType:
                String filePath = file.getPath();
                String extension = filePath.substring(filePath.lastIndexOf('.') + 1);
                Types.MIME mime = Types.MIME.get(extension);
                value = mime.toString();
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
                value = "identity";
                break;

            case Allow:
                value = "GET, HEAD, POST";
                break;

            case Expires:
                Calendar c = Calendar.getInstance();
                c.setTime(new Date());
                c.add(Calendar.HOUR, 24);
                Date expires = c.getTime();
                value = formatDate(expires);
                break;

            default:
                return "";
        }
        return String.format(headerLine, value);
    }

    private byte[] buildPayload(File resource) throws IOException {
        return Files.readAllBytes(resource.toPath());
    }

    private File getResource(String resource) {
        Path path = Paths.get("." + resource);
        return path.toFile();
    }

    private void send(String str) {
        System.out.printf("INFO: Sending String %n\"%s\" to socket.%n", str);
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
