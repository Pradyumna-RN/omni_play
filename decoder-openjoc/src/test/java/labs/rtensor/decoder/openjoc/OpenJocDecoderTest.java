package labs.rtensor.decoder.openjoc;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DummyTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class OpenJocDecoderTest {
  @Before public void nativeLibrary() {
    if (Boolean.getBoolean("rtensor.openjoc.expectNative")) assertTrue(OpenJocLibrary.unavailableReason(),OpenJocLibrary.isAvailable());
    else assumeTrue(OpenJocLibrary.isAvailable());
  }
  private byte[] recording() throws Exception {
    String name=System.getProperty("rtensor.openjoc.recording");assumeTrue("Supply an authorized local JOC fixture",name!=null);
    byte[] full=Files.readAllBytes(Path.of(name));int end=0;
    for(int i=0;i<300;i++){assertEquals(11,full[end]&255);assertEquals(119,full[end+1]&255);end+=((((full[end+2]&7)<<8)|(full[end+3]&255))+1)*2;}
    return Arrays.copyOf(full,end);
  }
  private static final class Result {
    byte[] pcm;long first=Long.MIN_VALUE;long end;boolean eos;int skipped;
  }
  private static void collect(OpenJocAudioDecoder d,ByteArrayOutputStream data,Result r) throws Exception {
    SimpleDecoderOutputBuffer out;
    while((out=d.dequeueOutputBuffer())!=null){
      try {
        if(out.isEndOfStream()){r.eos=true;r.end=out.timeUs;continue;}
        if(r.first==Long.MIN_VALUE)r.first=out.timeUs;
        r.skipped+=out.skippedOutputBufferCount;
        byte[] bytes=new byte[out.data.remaining()];out.data.get(bytes);data.write(bytes);
      } finally {out.release();}
    }
  }
  private Result decode(OpenJocAudioDecoder d,byte[] encoded,int group,long anchor) throws Exception {
    Result r=new Result();ByteArrayOutputStream pcm=new ByteArrayOutputStream();int at=0;boolean eosQueued=false;
    long deadline=System.nanoTime()+60_000_000_000L;
    while(!r.eos && System.nanoTime()<deadline){
      if(!eosQueued){DecoderInputBuffer in=d.dequeueInputBuffer();if(in!=null){
        in.timeUs=anchor+(long)(at/3072)*32_000;
        if(at==encoded.length){in.addFlag(C.BUFFER_FLAG_END_OF_STREAM);eosQueued=true;}
        else {int n=Math.min(group*3072,encoded.length-at);in.ensureSpaceForWrite(n);in.data.put(encoded,at,n);in.flip();at+=n;}
        d.queueInputBuffer(in);
      }}
      // Do not busy-spin against the native worker while the other Android
      // suites are compiling or running on the same host.
      collect(d,pcm,r);Thread.sleep(1);
    }
    assertTrue("Decoder must finish and drain",r.eos);r.pcm=pcm.toByteArray();return r;
  }
  @Test public void decoderDeclaresAnExplicit714Layout() throws Exception {
    OpenJocAudioDecoder d=new OpenJocAudioDecoder(65536);
    try {assertEquals(12,d.getChannelCount());assertEquals(12,Long.bitCount(d.getChannelLayoutMask()));assertEquals(48000,d.getSampleRate());}
    finally {d.release();d.release();}
  }
  @Test public void realMusicContainerIsIdentifiedAsJocBeforeRendererSelection() throws Exception {
    String name=System.getProperty("rtensor.openjoc.recording");assumeTrue("Supply an authorized local JOC fixture",name!=null);
    Path compressed=Path.of(name);
    Path container=compressed.resolveSibling(compressed.getFileName().toString().replaceFirst("\\.eac3$", ".m4a"));
    assertTrue("Supply the matching MP4/M4A reference container",Files.isRegularFile(container));
    byte[] bytes=Files.readAllBytes(container);
    Format[] audio={null};int[] samples={0};long[] duration={C.TIME_UNSET};
    Mp4Extractor extractor=new Mp4Extractor();
    extractor.init(new ExtractorOutput() {
      @Override public TrackOutput track(int id,int type) {
        return new TrackOutput() {
          private final DummyTrackOutput discard=new DummyTrackOutput();
          @Override public int sampleData(androidx.media3.common.DataReader input,int length,boolean allowEnd,int part) throws java.io.IOException {
            return discard.sampleData(input,length,allowEnd,part);
          }
          @Override public void sampleData(androidx.media3.common.util.ParsableByteArray data,int length,int part) {
            discard.sampleData(data,length,part);
          }
          @Override public void format(Format format) { if(type==C.TRACK_TYPE_AUDIO)audio[0]=format; }
          @Override public void sampleMetadata(long timeUs,int flags,int size,int offset,TrackOutput.CryptoData crypto) {
            if(type==C.TRACK_TYPE_AUDIO){assertTrue(size>0);samples[0]++;}
          }
        };
      }
      @Override public void endTracks() {}
      @Override public void seekMap(SeekMap map) { duration[0]=map.getDurationUs(); }
    });
    ByteArrayDataSource source=new ByteArrayDataSource(bytes);
    PositionHolder position=new PositionHolder();long at=0;boolean done=false;
    try {
      for(int reopen=0;reopen<100 && !done;reopen++) {
        source.open(new DataSpec.Builder().setUri("memory://authorized-reference").setPosition(at).build());
        DefaultExtractorInput input=new DefaultExtractorInput(source,at,bytes.length);
        int status=Extractor.RESULT_CONTINUE;
        while(status==Extractor.RESULT_CONTINUE)status=extractor.read(input,position);
        source.close();done=status==Extractor.RESULT_END_OF_INPUT;at=position.position;
      }
      assertTrue(done);assertNotNull(audio[0]);assertEquals(MimeTypes.AUDIO_E_AC3_JOC,audio[0].sampleMimeType);
      assertEquals(48000,audio[0].sampleRate);assertTrue(audio[0].channelCount>2);assertTrue(samples[0]>100);
      assertTrue(duration[0]>186_000_000 && duration[0]<194_000_000);
    } finally {source.close();extractor.release();}
  }
  @Test public void realJocHasHeightAudioAndDrainsBeforeEos() throws Exception {
    byte[] encoded=recording();OpenJocAudioDecoder d=new OpenJocAudioDecoder(65536);
    try {
      Result r=decode(d,encoded,6,0);
      assertEquals((300*1536+32)*12*4,r.pcm.length);
      assertEquals(0,r.first);assertTrue(d.getLatencySamples()>0);
      ByteBuffer samples=ByteBuffer.wrap(r.pcm).order(ByteOrder.nativeOrder());double heights=0;boolean audio=false;
      for(int i=0;samples.hasRemaining();i++){float v=samples.getFloat();assertTrue(Float.isFinite(v));audio|=Math.abs(v)>1e-6f;if(i%12>=8)heights+=v*v;}
      assertTrue(audio);assertTrue("Decoded objects must reach height speakers",heights>1e-5);
    } finally {d.release();}
  }
  @Test public void packetGroupingDoesNotChangeThePcm() throws Exception {
    byte[] encoded=recording();byte[] expected=null;
    for(int group:new int[]{1,2,6}){
      OpenJocAudioDecoder d=new OpenJocAudioDecoder(65536);
      try {byte[] actual=decode(d,encoded,group,0).pcm;if(expected==null)expected=actual;else assertArrayEquals(expected,actual);}
      finally {d.release();}
    }
  }
  @Test public void flushRecreatesTheSameAudioWithANewTimestampAnchor() throws Exception {
    byte[] encoded=recording();OpenJocAudioDecoder d=new OpenJocAudioDecoder(65536);
    try {
      Result first=decode(d,encoded,2,0);d.flush();Result second=decode(d,encoded,6,17_000_000);
      assertArrayEquals(first.pcm,second.pcm);assertEquals(17_000_000,second.first);
      assertEquals(first.end+17_000_000,second.end);
    } finally {d.release();}
  }
  @Test public void prerollIsTrimmedAtTheRequestedSeekTime() throws Exception {
    byte[] encoded=recording();byte[] expected;
    OpenJocAudioDecoder full=new OpenJocAudioDecoder(65536);
    try {expected=decode(full,encoded,6,0).pcm;}finally{full.release();}
    OpenJocAudioDecoder seek=new OpenJocAudioDecoder(65536);
    try {
      seek.setOutputStartTimeUs(500_000);Result actual=decode(seek,encoded,6,0);
      assertTrue(actual.first>=500_000);assertTrue(actual.skipped>0);
      assertArrayEquals(Arrays.copyOfRange(expected,24000*12*4,expected.length),actual.pcm);
    }finally{seek.release();}
  }
  @Test public void aFlushDiscardsQueuedAndHeldInput() throws Exception {
    byte[] encoded=recording();OpenJocAudioDecoder d=new OpenJocAudioDecoder(65536);
    try {
      DecoderInputBuffer in=d.dequeueInputBuffer();in.data.put(encoded,0,3072);in.flip();d.queueInputBuffer(in);
      DecoderInputBuffer held=d.dequeueInputBuffer();assertNotNull(held);d.flush();
      Result actual=decode(d,encoded,6,3_000_000);assertEquals(3_000_000,actual.first);
      assertEquals((300*1536+32)*12*4,actual.pcm.length);
    }finally{d.release();}
  }
}
