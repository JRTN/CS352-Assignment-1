import java.util.*;

public class ArgumentDecoder {

    private final TreeMap<String, String> argumentsMap;

    public ArgumentDecoder(String argumentString) {
        argumentsMap = new TreeMap<>();
        extractArguments(argumentString);
    }

    public String cleanArgument(String argument) {
        //Find and replace escaped characters with their unescaped counterpart
        String pattern = "(\\!)([\\!\\*'\\(\\);:@&\\+,/\\?#\\[\\]\\s])";

        return argument.replaceAll(pattern, "$2");
    }

    private void extractArguments(String argumentString) {
        //Split arguments on & signs not preceded by a ! but do split on those
        //preceded by a !! (escaped !)
        String[] arguments = argumentString.split("(?<!([^!]\\!))&");
        for(String argument : arguments) {
            //Split variable names from their value
            String[] parts = argument.split("=");
            if(parts.length == 2) {
                //Clean value of escaped characters and store in the map
                argumentsMap.put(parts[0], cleanArgument(parts[1]));
            }
        }
    }

    public List<String> getVariables() {
        //Return all the variable names as a String list
        return new LinkedList<>(argumentsMap.keySet());
    }

    public String getValue(String variableName) {
        //Return the value for a given variable name
        return argumentsMap.get(variableName);
    }

    public String getDecoded() {
        Iterator<String> iter = argumentsMap.keySet().iterator();

        StringBuilder builder = new StringBuilder();
        while(iter.hasNext()) {
            String key = iter.next();
            builder.append(key).append("=").append(argumentsMap.get(key));
            if(iter.hasNext()) {
                builder.append("&");
            }
        }

        return builder.toString();
    }
}
