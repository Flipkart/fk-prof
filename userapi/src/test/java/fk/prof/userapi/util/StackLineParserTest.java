package fk.prof.userapi.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**Test class for testing conversion of Signature based stackLines to java type based stackLines
 * Created by rohit.patiyal on 26/04/17.
 */
public class StackLineParserTest {

    private static Map<String, String> expectedIOMap = new HashMap<String, String>(){
            {
                put("Ljava/lang/String;#intern ()Ljava/lang/String;","java.lang.String.intern-()-java.lang.String");
                put("Ljava/lang/Long;#getChars (JI[[C)V","java.lang.Long.getChars-(long,int,char[][])-void");
                put("Ljava/time/format/DateTimeFormatterBuilder$CompositePrinterParser;#format (Ljava/time/format/DateTimePrintContext;Ljava/lang/StringBuilder;)Z","java.time.format.DateTimeFormatterBuilder$CompositePrinterParser.format-(java.time.format.DateTimePrintContext,java.lang.StringBuilder)-boolean");
                put("Lcom/fasterxml/jackson/core/JsonFactory$Feature;#enabledIn (I)Z","com.fasterxml.jackson.core.JsonFactory$Feature.enabledIn-(int)-boolean");
                put("Lcom/test;#hello_CAP_123 (I)Z","com.test.hello_CAP_123-(int)-boolean");
                put("~ ROOT ~.()","~ ROOT ~.()");
                put("~ UNCLASSIFIABLE ~.():0","~ UNCLASSIFIABLE ~.():0");
            }
    };

    @Test
    public void testConvertSignToJava() throws Exception {
        for(String testInput: expectedIOMap.keySet()){
            String got = StackLineParser.convertSignToJava(testInput);
            Assert.assertTrue(expectedIOMap.get(testInput).equals(got));
        }
    }

}