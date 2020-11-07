import java.util.*;

public class ArgumentDecoder {

    private final HashMap<String, String> argumentsMap;

    public ArgumentDecoder(String argumentString) {
        argumentsMap = new HashMap<>();
        extractArguments(argumentString);
    }

    private String cleanArgument(String argument) {
        //Find and replace escaped characters with their unescaped counterpart
        String pattern = "(\\!)([\\!\\*'\\(\\);:@&\\+,/\\?#\\[\\]\\s])";

        return argument.replaceAll(pattern, "$2");
    }

    private void extractArguments(String argumentString) {
        //Split arguments on & signs not preceeded by a !
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

    @Override
    public String toString() {
        return Collections.singletonList(argumentsMap).toString();
    }
}
