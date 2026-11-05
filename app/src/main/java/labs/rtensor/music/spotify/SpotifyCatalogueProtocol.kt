package labs.rtensor.music.spotify

import android.os.Parcel
import android.os.ResultReceiver

/** Only the framework receiver's Binder crosses processes, never its app subclass name.
 * readParcelable with the framework class loader cannot load the caller's anonymous receiver. */
internal object SpotifyCatalogueProtocol {
    fun writeReceiver(parcel: Parcel, receiver: ResultReceiver) = receiver.writeToParcel(parcel, 0)
    fun readReceiver(parcel: Parcel): ResultReceiver = ResultReceiver.CREATOR.createFromParcel(parcel)
}
