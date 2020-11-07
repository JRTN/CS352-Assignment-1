final public class Types {
    /*
        Defines the status codes which we implement in our http server. Response codes consist of
        an integer response code and a string message describing what that code means.
     */
    enum StatusCode {
        OK(200, "OK"),
        NO_CONTENT(204, "No Content"),
        NOT_MODIFIED(304, "Not Modified"),
        BAD_REQUEST(400, "Bad Request"),
        FORBIDDEN(403, "Forbidden"),
        NOT_FOUND(404, "Not Found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
        REQUEST_TIMEOUT(408, "Request Timeout"),
        LENGTH_REQUIRED(411, "Length Required"),
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

        /*
            Returns the response in the appropriate format for HTTP status lines
         */
        @Override
        public String toString() {
            return String.format("%d %s", code, message);
        }
    }

    /*
        Defines the MIME types and their associated extension. Each MIME type
        consists of three parts
            1. The extension which indicates it is of that type
            2. The type of the data
            3. The subtype of the data
     */
    enum MIME {

        HTML("html", "text", "html"),
        TXT("txt", "text", "plain"),
        GIF("gif", "image", "gif"),
        JPEG("jpeg", "image", "jpeg"),
        PNG("png", "image", "png"),
        PDF("pdf", "application", "pdf"),
        OCTETSTREAM("", "application", "octet-stream");

        private final String extension;
        private final String type;
        private final String subtype;

        MIME(String ext, String t, String s) {
            extension = ext;
            type = t;
            subtype = s;
        }

        /*
            Gets the appropriate MIME type from a file extension. This is achieved
            by checking each type to see if the extension matches.
         */
        public static MIME get(String ext) {
            for (MIME mime : MIME.values()) {
                if (mime.extension.equals(ext)) {
                    return mime;
                }
            }
            return OCTETSTREAM;
        }


        /*
            Returns the MIME type in the appropriate format for HTTP header lines
         */
        @Override
        public String toString() {
            return String.format("%s/%s", type, subtype);
        }
    }

    /*
        The header fields which can be found in HTTP responses. Consists only
        of the header field string.
     */
    enum HeaderField {

        ContentType("Content-Type"),
        ContentLength("Content-Length"),
        LastModified("Last-Modified"),
        ContentEncoding("Content-Encoding"),
        Allow("Allow"),
        Expires("Expires"),
        From("From"),
        UserAgent("User-Agent");

        public String fieldName;

        HeaderField(String name) {
            fieldName = name;
        }

        @Override
        public String toString() {
            return fieldName;
        }
    }
}
