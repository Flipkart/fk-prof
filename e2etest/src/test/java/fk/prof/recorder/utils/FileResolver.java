package fk.prof.recorder.utils;

import org.apache.commons.lang3.mutable.Mutable;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by gaurav.ashok on 06/03/17.
 */
public class FileResolver {

    public static Path resourceFile(String filename) throws Exception {
        URL resource = FileResolver.class.getResource(filename);
        return Paths.get(resource.toURI()).toAbsolutePath();
    }

    public static List<Path> findFile(String dirPath, String regex) {
        File dir = new File(dirPath);
        File[] files = dir.listFiles((d,f) -> f.matches(regex));
        return Stream.of(files).filter(f -> f.isFile()).map(f -> Paths.get(dirPath, f.getName())).collect(Collectors.toList());
    }

    public static Path jarFile(String tag, String dir, String regex, Mutable<Boolean> isFatJar) {
        List<Path> jars = FileResolver.findFile(dir, regex);

        assert jars != null && jars.size() > 0 : "jar for " + tag + " not found";

        // currently no module has fat in the name, so naively checking for its presence.
        Optional<Path> fatJar = jars.stream().filter(j -> j.getFileName().toString().toLowerCase().contains("fat")).findFirst();

        if(fatJar.isPresent()) {
            isFatJar.setValue(true);
            return fatJar.get();
        }

        isFatJar.setValue(false);
        // return first one
        return jars.get(0);
    }
}
