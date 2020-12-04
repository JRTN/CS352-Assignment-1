import java.util.Date;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Objects;

public class Cookie {

    private static HashMap<Integer, Cookie> cookieMap;

    static {
        cookieMap = new HashMap<>();
    }

    private int sessionID;
    private Date expires;

    public Cookie(String pw) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        cal.add(Calendar.SECOND, 30);
        expires = cal.getTime();

        sessionID = calculateCheckSum(pw);

        cookieMap.put(sessionID, this);
    }

    private int calculateCheckSum(String pw) {
        int checksum = 0;
        for(char c : pw.toCharArray()) {
            checksum += (int) c;
        }
        return checksum;
    }

    public int getSessionID() {
        return sessionID;
    }

    public Date getExpiresDate() {
        return expires;
    }

    public boolean isExpired() {
        return new Date().after(expires);
    }

    public static Cookie getCookieBySessionID(int id) {
        return cookieMap.get(id);
    }

    @Override
    public String toString() {
        return String.format("SessionID: %d Expires: %s", sessionID, expires.toString());
    }
}