package app.aapswear.mobile

import android.content.Context
import app.aapswear.protocol.WearProtocol
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

internal suspend fun requestWatchFaceApply(
    context: Context,
    index: Int,
): Int {
    val nodes =
        Wearable
            .getNodeClient(context)
            .connectedNodes
            .await()

    val payload =
        index
            .coerceAtLeast(0)
            .toString()
            .encodeToByteArray()

    nodes.forEach { node ->
        Wearable
            .getMessageClient(context)
            .sendMessage(
                node.id,
                WearProtocol.WATCH_FACE_APPLY_PATH,
                payload,
            ).await()
    }

    return nodes.size
}
