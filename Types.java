import java.util.Arrays;

final public class Types {
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

    enum MIME {

        HTML("html", "text", "html"),
        TXT("txt", "text", "plain"),
        GIF("gif", "image", "gif"),
        JPEG("jpeg", "image", "jpeg"),
        PNG("png", "image", "png"),
        PDF("pdf", "application", "pdf"),
        OCTETSTREAM(null, "application", "octet-stream");

        private final String extension;
        private final String type;
        private final String subtype;

        MIME(String ext, String t, String s) {
            extension = ext;
            type = t;
            subtype = s;
        }

        public static MIME get(String ext) {
            for (MIME mime : Arrays.asList(HTML, TXT, GIF, JPEG, PNG, PDF)) {
                if (mime.extension.equals(ext)) {
                    return mime;
                }
            }
            return OCTETSTREAM;
        }


        @Override
        public String toString() {
            return String.format("%s/%s", type, subtype);
        }
    }

    enum HeaderField {

        ContentType("Content-Type"),
        ContentLength("Content-Length"),
        LastModified("Last-Modified"),
        ContentEncoding("Content-Encoding"),
        Allow("Allow"),
        Expires("Expires");

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