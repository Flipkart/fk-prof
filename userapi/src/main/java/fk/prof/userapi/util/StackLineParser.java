package fk.prof.userapi.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class which parses and converts Java names from their corresponding JVM Types signatures
 * from string of format className#methodName (argTypes)retType
 * Created by rohit.patiyal on 26/04/17.
 */
public class StackLineParser {

    /**
     * Map of JVM Type signtures to their Java representation names for primitive types
     */
    private static Map<Character, String> signToPrimitiveMap = new HashMap<Character, String>() {
        {
            put('V', "void");
            put('Z', "boolean");
            put('B', "byte");
            put('C', "char");
            put('S', "short");
            put('I', "int");
            put('J', "long");
            put('F', "float");
            put('D', "double");
        }
    };

    /**
     * List of parsers which enforces the format of input which is :
     * 1. ClassName (A Type)
     * 2. MethodName
     * 3. Arguments (Collection of Types of the arguments enclosed in '(' and ')'
     * 4. Return Type (A Type)
     */
    private static List<JVMTypeSignatureParser> parsers = new ArrayList<JVMTypeSignatureParser>() {
        {
            add(StackLineParser::parseClassName);
            add(StackLineParser::parseMethodName);
            add(StackLineParser::parseArgs);
            add(StackLineParser::parseReturnType);
        }
    };

    /**
     * Converts the raw string from JVM Types signatures format to Java representation
     *
     * @param raw String to be parsed and converted to its Java representation
     * @return Converted string
     */
    public static String convertJVMTypeSignToJava(String raw) {
        StringBuilder parsed = new StringBuilder();
        int idx = 0;
        int parsedLength = 0;
        raw = raw.replace('/', '.'); //Replace occurrences of / with . before even starting to parse

        //Apply parsers (in order as per structure of the stackLine),
        //parser list enforces the format of the stackLine.
        //Loop breaks with partial successful result
        //in case of failure in any parse step
        for (JVMTypeSignatureParser parser : parsers) {
            int res = parser.parse(parsed, raw, idx);
            if (res == -1) {
                return parsed.substring(0, parsedLength) + raw.substring(idx);
            }
            idx = res;
            parsedLength = parsed.length();
        }
        return parsed.toString();
    }

    /**
     * Functional Interface representing a parser function which takes three inputs
     * 1. string builder to store the parsed and converted string,
     * 2. the string to be parsed and converted
     * 3. index in raw input string to start parsing from
     * and outputs the next index in raw input to parse from or -1 if fails
     */
    @FunctionalInterface
    private interface JVMTypeSignatureParser {
        int parse(StringBuilder parsed, String raw, int idx);
    }

    /**
     * Parses and appends converted input in parsed stringBuilder from raw string
     * if the input is a full class name representing the class name of the method
     * in the stackLine
     *
     * @param parsed String builder containing the parsed and converted string
     * @param raw    String which is to be parsed
     * @param idx    Index in raw string starting from which the string is to be parsed
     * @return Next index in raw string to parse from and -1 if receives unexpected input
     */
    private static int parseClassName(StringBuilder parsed, String raw, int idx) {
        if (idx >= raw.length()) return -1;
        char currChar = raw.charAt(idx);
        if (currChar == 'L') {
            int endIndex = raw.indexOf(';', idx); //full class name ends with a semi colon in the input
            if (endIndex == -1) return -1;
            parsed.append(raw, idx + 1, endIndex);
            return endIndex + 1;
        }
        return -1;
    }

    /**
     * Parses and appends converted input in parsed stringBuilder from raw string
     * if the input is a type either a full class name, a primitive type or its n-array
     *
     * @param parsed String builder containing the parsed and converted string
     * @param raw    String which is to be parsed
     * @param idx    Index in raw string starting from which the string is to be parsed
     * @return Next index in raw string to parse from and -1 if receives unexpected input
     */
    private static int parseType(StringBuilder parsed, String raw, int idx) {
        if (idx >= raw.length()) return -1;
        char currChar = raw.charAt(idx);
        if (currChar == '[') {  //represents start of array type
            int nextIndex = parseType(parsed, raw, idx + 1);
            parsed.append("[]");
            return nextIndex;
        } else if (currChar == 'L') { //marks the start of a full class name
            return parseClassName(parsed, raw, idx);
        } else if (signToPrimitiveMap.containsKey(currChar)) { // implies that it is a primitive type
            parsed.append(signToPrimitiveMap.get(currChar));
            return idx + 1;
        }
        return -1;
    }

    /**
     * Parses and appends converted input in parsed stringBuilder from raw string if the input is
     * collection of types marked by '(' bracket at start and ')' at the end. The collection can be empty.
     *
     * @param parsed String builder containing the parsed and converted string
     * @param raw    String which is to be parsed
     * @param idx    Index in raw string starting from which the string is to be parsed
     * @return Next index in raw string to parse from and -1 if receives unexpected input
     */
    private static int parseArgs(StringBuilder parsed, String raw, int idx) {
        if (idx >= raw.length()) return -1;
        if (raw.charAt(idx) == '(') {
            parsed.append("(");
            idx += 1;
            boolean argsFound = false; //identifier whether atleast one arg was found to handle last comma correctly
            int i = idx;
            while (i < raw.length() && raw.charAt(i) != ')' && (i = parseType(parsed, raw, idx)) != -1) {
                parsed.append(',');
                idx = i;
                argsFound = true;
            }
            if (i >= raw.length()) return -1;
            if (raw.charAt(idx) == ')') {
                idx += 1;
                if (argsFound) {
                    parsed.setCharAt(parsed.length() - 1, ')'); //consuming the last comma if atleast one arg found
                } else {
                    parsed.append(')');
                }
                return idx;
            }
        }
        return -1;
    }

    /**
     * Parses and appends converted input in parsed stringBuilder from raw string if the input
     * starts with '#' hash symbol which marks the start of a Java method name with a space to mark its end
     *
     * @param parsed String builder containing the parsed and converted string
     * @param raw    String which is to be parsed
     * @param idx    Index in raw string starting from which the string is to be parsed
     * @return Next index in raw string to parse from and -1 if receives unexpected input
     */
    private static int parseMethodName(StringBuilder parsed, String raw, int idx) {
        if (idx >= raw.length()) return -1;
        if (raw.charAt(idx) == '#') {
            parsed.append(".");
            int endIndex = raw.indexOf(" ");   //index where the method name ends
            if (endIndex == -1) return -1;
            parsed.append(raw, idx + 1, endIndex);
            parsed.append(" "); //appending space without any conversion
            return endIndex + 1;
        }
        return -1;
    }

    /**
     * Parses and appends converted input in parsed stringBuilder from raw string
     * if the input is a full class name, a primitive type or its n-array representing the
     * return type of the stack line
     *
     * @param parsed String builder containing the parsed and converted string
     * @param raw    String which is to be parsed
     * @param idx    Index in raw string starting from which the string is to be parsed
     * @return Next index in raw string to parse from and -1 if receives unexpected input
     */
    private static int parseReturnType(StringBuilder parsed, String raw, int idx) {
        return parseType(parsed, raw, idx);
    }

}
