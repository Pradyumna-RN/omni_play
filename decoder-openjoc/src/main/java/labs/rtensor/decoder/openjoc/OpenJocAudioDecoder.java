package labs.rtensor.decoder.openjoc;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** Serial native decoding with bounded buffer pools. EOS drains the reconstruction
 * filters before the EOS buffer; flush/reset and native destruction stay on one owner. */
@UnstableApi
public final class OpenJocAudioDecoder implements Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, OpenJocException> {
  private static final int CHANNELS = 12;
  private static final int RATE = 48000;
  private final Object lock = new Object();
  private final ArrayDeque<DecoderInputBuffer> inputs = new ArrayDeque<>();
  private final ArrayDeque<DecoderInputBuffer> freeInputs = new ArrayDeque<>();
  private final ArrayDeque<SimpleDecoderOutputBuffer> outputs = new ArrayDeque<>();
  private final ArrayDeque<SimpleDecoderOutputBuffer> freeOutputs = new ArrayDeque<>();
  private final Thread worker;
  private final long handle;
  private DecoderInputBuffer dequeued;
  private OpenJocException error;
  private boolean released;
  private long generation;
  private long outputStartTimeUs = Long.MIN_VALUE;
  private volatile int latencySamples;

  public OpenJocAudioDecoder(int initialInputBytes) throws OpenJocException {
    if (!OpenJocLibrary.isAvailable()) throw new OpenJocException("OpenJOC library unavailable: " + OpenJocLibrary.unavailableReason());
    if (initialInputBytes < 0 || initialInputBytes > 1048576) throw new OpenJocException("Unsupported JOC input block size");
    handle = nativeCreate();
    if (handle == 0) throw new OpenJocException("OpenJOC speaker decoder initialization failed");
    try {
      for (int i=0;i<8;i++) {
        DecoderInputBuffer in = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        in.ensureSpaceForWrite(Math.max(32768, initialInputBytes)); freeInputs.add(in);
        freeOutputs.add(new SimpleDecoderOutputBuffer(this::returnOutput));
      }
      worker = new Thread(this::run, "Music-OpenJOC");
      worker.start();
    } catch (Throwable t) { nativeDestroy(handle); throw new OpenJocException("OpenJOC buffer initialization failed", t); }
  }

  @Override public String getName() { return "openjoc-0.20.1-speaker-experimental"; }
  public int getChannelCount() { return CHANNELS; }
  public int getSampleRate() { return RATE; }
  public int getLatencySamples() { return latencySamples; }
  /** FFmpeg/AV channel-mask order: FL FR FC LFE BL BR SL SR TFL TFR TBL TBR. */
  public long getChannelLayoutMask() { return 0x3fL | (1L<<9) | (1L<<10) | (1L<<12) | (1L<<14) | (1L<<15) | (1L<<17); }
  @Override public void setOutputStartTimeUs(long timeUs) { synchronized(lock) { outputStartTimeUs=timeUs; } }
  private void check() throws OpenJocException { if(error!=null)throw error; if(released)throw new OpenJocException("Decoder is closed"); }

  @Override public DecoderInputBuffer dequeueInputBuffer() throws OpenJocException {
    synchronized(lock) {
      check(); if(dequeued!=null)throw new IllegalStateException("Queue the previous input first");
      dequeued=freeInputs.poll(); return dequeued;
    }
  }
  @Override public void queueInputBuffer(DecoderInputBuffer input) throws OpenJocException {
    synchronized(lock) {
      check(); if(input!=dequeued || input==null)throw new IllegalArgumentException("Input is not owned by this decoder");
      if(input.isEncrypted())throw new OpenJocException("Protected input is not supported by this decoder");
      inputs.add(input);dequeued=null;lock.notifyAll();
    }
  }
  @Override public SimpleDecoderOutputBuffer dequeueOutputBuffer() throws OpenJocException {
    synchronized(lock) { check();return outputs.poll(); }
  }
  private void returnInput(DecoderInputBuffer input) { input.clear();freeInputs.add(input); }
  private void returnOutput(SimpleDecoderOutputBuffer output) {
    synchronized(lock) { output.clear();if(!released)freeOutputs.add(output);lock.notifyAll(); }
  }
  @Override public void flush() {
    synchronized(lock) {
      generation++;error=null;
      if(dequeued!=null){returnInput(dequeued);dequeued=null;}
      while(!inputs.isEmpty())returnInput(inputs.remove());
      while(!outputs.isEmpty())returnOutput(outputs.remove());
      lock.notifyAll();
    }
  }
  @Override public void release() {
    synchronized(lock) { if(released)return;released=true;lock.notifyAll(); }
    boolean interrupted=false;
    while(true) { try { worker.join();break; } catch(InterruptedException e){interrupted=true;} }
    nativeDestroy(handle);
    if(interrupted)Thread.currentThread().interrupt();
  }

  private void run() {
    long workerGeneration=-1;
    long anchorUs=C.TIME_UNSET;
    boolean emitEos=false;
    long eosTimeUs=0;
    int skipped=0;
    try {
      while(true) {
        DecoderInputBuffer input;
        SimpleDecoderOutputBuffer output;
        long epoch;
        boolean reset;
        boolean eosOnly;
        synchronized(lock) {
          while(!released && (error!=null || freeOutputs.isEmpty() || (inputs.isEmpty() && (!emitEos || workerGeneration!=generation))))lock.wait();
          if(released)return;
          epoch=generation;reset=workerGeneration!=epoch;
          if(reset){emitEos=false;anchorUs=C.TIME_UNSET;skipped=0;workerGeneration=epoch;}
          eosOnly=emitEos;
          input=eosOnly?null:inputs.remove();output=freeOutputs.remove();
        }
        try {
          if(reset && nativeReset(handle)!=0)throw new OpenJocException("OpenJOC reset failed");
          if(eosOnly) {
            output.timeUs=eosTimeUs;output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);emitEos=false;
          } else {
            if(anchorUs==C.TIME_UNSET)anchorUs=input.timeUs;
            boolean eos=input.isEndOfStream();
            ByteBuffer data=input.data;
            if(!eos && (data==null || !data.isDirect()))throw new OpenJocException("Expected direct compressed input");
            int bytes=nativeDecode(handle,data,eos?0:data.position(),eos?0:data.remaining(),eos);
            if(bytes<0)throw new OpenJocException("JOC object decoding: " + nativeError(handle));
            latencySamples=nativeLatency(handle);
            long pts=anchorUs+nativeFirstPts(handle)*1_000_000L/RATE;
            if(bytes>0) {
              ByteBuffer pcm=output.init(pts,bytes).order(ByteOrder.nativeOrder());
              if(nativeCopy(handle,pcm)!=0)throw new OpenJocException("JOC output buffer copy failed");
            }
            if(eos){emitEos=true;eosTimeUs=pts+(long)(bytes/(CHANNELS*4))*1_000_000L/RATE;}
            if(bytes==0) {
              synchronized(lock) {
                returnInput(input);input=null;returnOutput(output);
                if(epoch!=generation)emitEos=false;
              }
              continue;
            }
          }
          synchronized(lock) {
            if(input!=null){returnInput(input);input=null;}
            if(released || epoch!=generation) { returnOutput(output);emitEos=false;continue; }
            if(!output.isEndOfStream()) {
              int frames=output.data.remaining()/(CHANNELS*4);
              long endUs=output.timeUs+(long)frames*1_000_000L/RATE;
              if(endUs<=outputStartTimeUs){skipped++;returnOutput(output);continue;}
              if(output.timeUs<outputStartTimeUs) {
                int trim=(int)Math.min(frames,Math.ceil((outputStartTimeUs-output.timeUs)*RATE/1_000_000.0));
                output.data.position(output.data.position()+trim*CHANNELS*4);
                output.timeUs+=(long)trim*1_000_000L/RATE;
              }
            }
            output.skippedOutputBufferCount=skipped;skipped=0;outputs.add(output);lock.notifyAll();
          }
        } catch(Throwable failure) {
          synchronized(lock) {
            if(input!=null)returnInput(input);returnOutput(output);emitEos=false;
            if(!released && epoch==generation) error=failure instanceof OpenJocException?(OpenJocException)failure:new OpenJocException("JOC decode worker failed",failure);
            lock.notifyAll();
          }
        }
      }
    } catch(InterruptedException e) { synchronized(lock){if(!released)error=new OpenJocException("JOC worker interrupted",e);} }
  }

  private static native long nativeCreate();
  private static native int nativeDecode(long handle, ByteBuffer input, int offset, int length, boolean eos);
  private static native int nativeCopy(long handle, ByteBuffer output);
  private static native long nativeFirstPts(long handle);
  private static native int nativeLatency(long handle);
  private static native String nativeError(long handle);
  private static native int nativeReset(long handle);
  private static native void nativeDestroy(long handle);
}
