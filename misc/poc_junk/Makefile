err_bt_f1=error_backtrace_different_compilation_unit_helper.cc
err_bt_f2=customizable_error_backtrace.cc

bt: $(err_bt_f1) $(err_bt_f2) Makefile
	clang++ -std=c++11 -o bt $(err_bt_f1) $(err_bt_f2) -g3 -ggdb -O0 -lelf -fno-omit-frame-pointer

BtPrinter.class: BtPrinter.java
	javac BtPrinter.java

BtPrinter.h: BtPrinter.class
	javah BtPrinter

bt.so: $(err_bt_f1) $(err_bt_f2) Makefile BtPrinter.h
	clang++ -std=c++11 --shared -o bt.so -DW_JNI $(err_bt_f1) $(err_bt_f2) -g3 -ggdb -O0 -lelf -fno-omit-frame-pointer -fPIC -I/usr/lib/jvm/oracle-jdk-bin-1.8/include -I/usr/lib/jvm/oracle-jdk-bin-1.8/include/linux

jni_bt: bt.so
	java BtPrinter
