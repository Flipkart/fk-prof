package fk.prof.aggregation;

import fk.prof.aggregation.model.IOSourceLookup;
import fk.prof.idl.Profile;
import fk.prof.idl.Recording;
import org.junit.Assert;
import org.junit.Test;


public class IOSourceLookupTest {

  @Test
  public void testGetAndAdd() {
    IOSourceLookup ioLookup = new IOSourceLookup();

    Recording.FDInfo fd1 = Recording.FDInfo.newBuilder().setId(100).setFdType(Recording.FDType.file).setFileInfo(Recording.FileInfo.newBuilder().setFilename("file").setFlags("1").build()).build();
    Recording.FDInfo fd2 = Recording.FDInfo.newBuilder().setId(200).setFdType(Recording.FDType.file).setFileInfo(Recording.FileInfo.newBuilder().setFilename("file").setFlags("1").build()).build();
    Recording.FDInfo fd3 = Recording.FDInfo.newBuilder().setId(100).setFdType(Recording.FDType.file).setFileInfo(Recording.FileInfo.newBuilder().setFilename("file").setFlags("2").build()).build();
    Recording.FDInfo fd4 = Recording.FDInfo.newBuilder().setId(300).setFdType(Recording.FDType.socket).setSocketInfo(Recording.SocketInfo.newBuilder().setAddress("socket").setConnect(true).build()).build();

    int srcId1 = ioLookup.getOrAdd(fd1);
    int srcId2 = ioLookup.getOrAdd(fd2);
    int srcId3 = ioLookup.getOrAdd(fd3);
    int srcId4 = ioLookup.getOrAdd(fd4);

    Assert.assertEquals(4, srcId1);
    Assert.assertEquals(4, srcId2);
    Assert.assertEquals(4, srcId3);
    Assert.assertEquals(5, srcId4);
  }

  @Test
  public void testReverseLookupAndReservedMethodIds() {
    Recording.FDInfo fd1 = Recording.FDInfo.newBuilder().setId(100).setFdType(Recording.FDType.file).setFileInfo(Recording.FileInfo.newBuilder().setFilename("file").setFlags("1").build()).build();
    Recording.FDInfo fd2 = Recording.FDInfo.newBuilder().setId(300).setFdType(Recording.FDType.socket).setSocketInfo(Recording.SocketInfo.newBuilder().setAddress("socket").setConnect(true).build()).build();

    IOSourceLookup ioLookup = new IOSourceLookup();
    int srcId1 = ioLookup.getOrAdd(fd1);
    int srcId2 = ioLookup.getOrAdd(fd2);

    Profile.IOSource[] reverseLookup = ioLookup.generateReverseLookup();
    Assert.assertEquals(6, reverseLookup.length);

    Assert.assertEquals(Profile.IOSource.newBuilder().setFdType(Recording.FDType.file).setUri("file").build(), reverseLookup[srcId1]);
    Assert.assertEquals(Profile.IOSource.newBuilder().setFdType(Recording.FDType.socket).setUri("socket").build(), reverseLookup[srcId2]);

    Assert.assertEquals(IOSourceLookup.FILE_INVALID_INFO, reverseLookup[IOSourceLookup.FILE_INVALID_ID]);
    Assert.assertEquals(IOSourceLookup.SOCKET_INVALID_INFO, reverseLookup[IOSourceLookup.SOCKET_INVALID_ID]);
  }

}
