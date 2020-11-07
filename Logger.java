public class Logger {
    public static void info(String message, String content) {
        System.out.printf("INFO: %s\n%s", message, indentContent(content));
    }

    public static void error(String message, String content) {
        System.out.printf("ERROR: %s\n%s", message, indentContent(content));
    }

    private static String indentContent(String content) {
        if(content == null) return "";
        return content.replaceAll("(?m)^", "\t") + "\n";
    }
}