package fk.prof.bciagent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
public @interface Profile {
  ProfileType[] value();
}
