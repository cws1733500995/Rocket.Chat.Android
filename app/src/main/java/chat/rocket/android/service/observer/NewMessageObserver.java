package chat.rocket.android.service.observer;

import android.content.Context;
import io.realm.Realm;
import io.realm.RealmResults;
import org.json.JSONObject;

import java.util.List;
import chat.rocket.android.api.MethodCallHelper;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.log.RCLog;
import chat.rocket.android.model.SyncState;
import chat.rocket.android.model.ddp.Message;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.service.DDPClientRef;

/**
 * Observe messages for sending.
 */
public class NewMessageObserver extends AbstractModelObserver<Message> {

  private final MethodCallHelper methodCall;

  public NewMessageObserver(Context context, String hostname,
                            RealmHelper realmHelper, DDPClientRef ddpClientRef) {
    super(context, hostname, realmHelper, ddpClientRef);
    methodCall = new MethodCallHelper(realmHelper, ddpClientRef);

    realmHelper.executeTransaction(realm -> {
      // resume pending operations.
      RealmResults<Message> pendingMethodCalls = realm.where(Message.class)
          .equalTo(Message.SYNC_STATE, SyncState.SYNCING)
          .findAll();
      for (Message message : pendingMethodCalls) {
        message.setSyncState(SyncState.NOT_SYNCED);
      }

      return null;
    }).continueWith(new LogcatIfError());
  }

  @Override
  public RealmResults<Message> queryItems(Realm realm) {
    return realm.where(Message.class)
        .equalTo(Message.SYNC_STATE, SyncState.NOT_SYNCED)
        .isNotNull(Message.ROOM_ID)
        .findAll();
  }

  @Override
  public void onUpdateResults(List<Message> results) {
    if (results.isEmpty()) {
      return;
    }

    final Message message = results.get(0);
    final String messageId = message.getId();
    final String roomId = message.getRoomId();
    final String msg = message.getMessage();

    realmHelper.executeTransaction(realm ->
        realm.createOrUpdateObjectFromJson(Message.class, new JSONObject()
            .put(Message.ID, messageId)
            .put(Message.SYNC_STATE, SyncState.SYNCING)
        )
    ).onSuccessTask(task -> methodCall.sendMessage(messageId, roomId, msg)
    ).continueWith(task -> {
      if (task.isFaulted()) {
        RCLog.w(task.getError());
        realmHelper.executeTransaction(realm ->
            realm.createOrUpdateObjectFromJson(Message.class, new JSONObject()
                .put(Message.ID, messageId)
                .put(Message.SYNC_STATE, SyncState.FAILED)));
      }
      return null;
    });
  }
}
