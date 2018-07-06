package fr.hostux.louis.koelouis.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import fr.hostux.louis.koelouis.Config;
import fr.hostux.louis.koelouis.PlayingActivity;
import fr.hostux.louis.koelouis.R;
import fr.hostux.louis.koelouis.helper.MediaStore;
import fr.hostux.louis.koelouis.helper.QueueHelper;
import fr.hostux.louis.koelouis.helper.SessionManager;
import fr.hostux.louis.koelouis.models.Album;
import fr.hostux.louis.koelouis.models.Artist;
import fr.hostux.louis.koelouis.models.Song;
import fr.hostux.louis.koelouis.models.User;

public class PlayerService extends MediaBrowserServiceCompat implements QueueHelper.OnQueueChangedListener, MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnSeekCompleteListener, AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_PLAY = "action_play";
    public static final String ACTION_PAUSE = "action_pause";
    public static final String ACTION_NEXT = "action_next";
    public static final String ACTION_PREVIOUS = "action_previous";
    public static final String ACTION_STOP = "action_stop";

    private MediaPlayer player;
    private static MediaStore mediaStore;
    private static QueueHelper queueHelper;
    private User user;
    private AudioManager audioManager;

    private MediaSessionCompat mediaSession;
    private MediaControllerCompat mediaController;

    private final IBinder playerBind = new PlayerBinder();

    enum State {
        Stopped,
        Buffering,
        Preparing,
        Playing,
        Paused
    };
    private State state = State.Stopped;

    enum PauseReason {
        UserRequest,
        FocusLoss,
    };
    private PauseReason pauseReason = PauseReason.UserRequest;

    enum AudioFocus {
        NoFocusNoDuck,    // we don't have audio focus, and can't duck
        NoFocusCanDuck,   // we don't have focus, but can play at a low volume ("ducking")
        Focused           // we have full audio focus
    }
    AudioFocus audioFocus = AudioFocus.NoFocusNoDuck;

    float DUCK_VOLUME = 0.2f;

    private NotificationManager notificationManager;
    int NOTIFY_ID = 27;

    private String MEDIA_ID_ROOT = "__ROOT__";

    public enum PlayMode {
        Normal,
        RepeatOne,
        RepeatAll
    };
    private PlayMode playMode = PlayMode.Normal;


    public PlayerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return playerBind;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        player.stop();
        player.release();
        mediaSession.release();
        
        return false;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        notificationManager.cancel(NOTIFY_ID);
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initPlayer();
        mediaStore = new MediaStore(getApplicationContext());
        queueHelper = new QueueHelper(mediaStore);
        queueHelper.setListener(this);

        user = new SessionManager(getApplicationContext()).getUser();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        initMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(mediaSession == null) {
            initMediaSession();
        }
        initPlayer();

        handleIntent(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    private void handleIntent(Intent intent) {
        if(intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();

        if( action.equalsIgnoreCase( ACTION_PLAY ) ) {
            mediaController.getTransportControls().play();
        } else if( action.equalsIgnoreCase( ACTION_PAUSE ) ) {
            mediaController.getTransportControls().pause();
        } else if( action.equalsIgnoreCase( ACTION_PREVIOUS ) ) {
            mediaController.getTransportControls().skipToPrevious();
        } else if( action.equalsIgnoreCase( ACTION_NEXT ) ) {
            mediaController.getTransportControls().skipToNext();
        } else if( action.equalsIgnoreCase( ACTION_STOP ) ) {
            mediaController.getTransportControls().stop();
            stopSelf();
        }
    }

    private Notification.Action generateAction( int icon, String title, String intentAction ) {
        Intent intent = new Intent( getApplicationContext(), PlayerService.class );
        intent.setAction( intentAction );
        PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 1, intent, 0);


        return new Notification.Action.Builder( icon, title, pendingIntent ).build();
    }

    private void buildNotification( Notification.Action action ) {
        Notification.MediaStyle style = new Notification.MediaStyle();


        Intent stopIntent = new Intent( getApplicationContext(), PlayerService.class );
        stopIntent.setAction( ACTION_STOP );
        PendingIntent stopPendingIntent = PendingIntent.getService(getApplicationContext(), 1, stopIntent, 0);

        Intent playingIntent = new Intent( getApplicationContext(), PlayingActivity.class );
        PendingIntent playingPendingIntent = PendingIntent.getActivity(getApplicationContext(), 1, playingIntent, 0);


        String songTitle = "-";
        String artistName = "-";
        if(getCurrent() != null) {
            songTitle = getCurrent().getTitle();
            artistName = getCurrent().getAlbum().getArtist().getName();
        }

        boolean ongoing = (state == State.Playing);

        Notification.Builder builder = new Notification.Builder( this )
                .setSmallIcon(R.drawable.logo)
                .setOngoing(ongoing)
                .setContentTitle(songTitle)
                .setContentText(artistName)
                .setDeleteIntent( stopPendingIntent )
                .setStyle(style)
                .setContentIntent(playingPendingIntent);

        builder.addAction( generateAction(R.drawable.ic_prev, "Previous", ACTION_PREVIOUS ) );
        builder.addAction( action );
        builder.addAction( generateAction(R.drawable.ic_next, "Next", ACTION_NEXT ) );

        style.setShowActionsInCompactView(0,1,2);

        Notification notification = builder.build();

        notificationManager.notify(NOTIFY_ID, notification);
    }
    
    private void requestAudioFocus() {
        Log.d("player", "requestAudioFocus");
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        if(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocus = AudioFocus.Focused;
        } else {
            audioFocus = AudioFocus.NoFocusNoDuck;
        }
    }

    public void initPlayer() {
        if(player == null) {
            player = new MediaPlayer();

            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK); // continue playing on idle
            player.setAudioStreamType(AudioManager.STREAM_MUSIC); // we are streaming music

            // bind to the listeners
            player.setOnPreparedListener(this);
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            player.setOnSeekCompleteListener(this);

            player.setVolume(1.0f, 1.0f);
        }
    }
    
    public void initMediaSession() {
        mediaSession = new MediaSessionCompat(getApplicationContext(), "koelouis");
        setSessionToken(mediaSession.getSessionToken());
        mediaController = mediaSession.getController();

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            // TODO: implement other (like setRating)

            /**
             * Override to handle requests to begin playback.
             */
            @Override
            public void onPlay() {
                Log.d("player", "onPlay");

                super.onPlay();
                processPlay();
            }

            /**
             * Override to handle requests to pause playback.
             */
            @Override
            public void onPause() {
                Log.d("player", "onPause");

                super.onPause();
                processPause();
            }

            /**
             * Override to handle requests to skip to the next media item.
             */
            @Override
            public void onSkipToNext() {
                Log.d("player", "onNext");

                super.onSkipToNext();
                processNext();
            }

            /**
             * Override to handle requests to skip to the previous media item.
             */
            @Override
            public void onSkipToPrevious() {
                Log.d("player", "onPrev");

                super.onSkipToPrevious();
                processPrev();
            }

            /**
             * Override to handle requests to stop playback.
             */
            @Override
            public void onStop() {
                Log.d("player", "onStop");

                super.onStop();
                stopPlayer();
            }

            /**
             * Override to handle requests to seek to a specific position in ms.
             *
             * @param pos New position to move to, in milliseconds.
             */
            @Override
            public void onSeekTo(long pos) {
                processSeekTo(pos);

                super.onSeekTo(pos);
            }
        });


        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setActive(true);
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    public class PlayerBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.d("player","onAudioFocusChange");
        switch(focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d("player","AUDIOFOCUS_GAIN");
                audioFocus = AudioFocus.Focused;

                if(player == null) {
                    initPlayer();
                }
                else if(!isPlaying() && pauseReason == PauseReason.FocusLoss) {
                    processPlay();
                }
                player.setVolume(1.0f, 1.0f);
                pauseReason = PauseReason.UserRequest;
                break;

            // For an unbounded amount of time : we release
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d("player","AUDIOFOCUS_LOSS");
                audioFocus = AudioFocus.NoFocusNoDuck;

                releasePlayer();
                break;

            // For a short time, have to stop
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d("player","AUDIOFOCUS_LOSS_TRANSIENT");
                audioFocus = AudioFocus.NoFocusNoDuck;

                if(isPlaying()) {
                    processPause(true);
                    pauseReason = PauseReason.FocusLoss;
                }
                break;

            // For a short time but we can continue
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d("player","AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                audioFocus = AudioFocus.NoFocusCanDuck;

                player.setVolume(DUCK_VOLUME, DUCK_VOLUME);
                break;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        startPlayer();
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        Log.d("player", "on completion");

        if(state == State.Playing) {
            nextSong();
        }
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
        player.reset();

        state = State.Stopped;
        updateMediaSessionMetadata();
        updateMediaSessionState();
        return false;
    }

    @Override
    public void onSeekComplete(MediaPlayer mediaPlayer) {
        state = State.Playing;
        updateMediaSessionState();
    }

    @Override
    public void updateQueue(List<Song> newQueue) {
        List<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
        int count = 0;

        for(Song song : newQueue) {
            MediaSessionCompat.QueueItem queueItem = new MediaSessionCompat.QueueItem(convertSongToMediaMetadata(song).getDescription(), count++);
            queue.add(queueItem);
        }

        mediaSession.setQueue(queue);
    }

    public boolean isPlaying() {
        if(state == State.Playing) {
            return true;
        } else {
            return false;
        }
    }
    public int getDuration() {
        return player.getDuration();
    }

    public List<Song> getQueue() {
        return queueHelper.getQueue();
    }

    public void clearQueue() {
        queueHelper.clearQueue();
    }

    public void removeFromQueue(int position) {
        queueHelper.removeFromQueue(position);
    }

    public void addToQueue(Song song) {
        queueHelper.add(song);
    }

    public void addNext(Song song) {
        queueHelper.addNext(song);
    }

    public Song getCurrent() {
        return queueHelper.getCurrent();
    }

    public void processSeekTo(long position) {
        if(isPlaying()) {
            player.seekTo((int) position);
            state = State.Buffering;

            updateMediaSessionState();
        }
    }
    public void processPlaySong(Song song) {
        queueHelper.clearAndAdd(song);
    }

    public void playArtist(Artist artist) {
        queueHelper.clearAndAddArtist(artist);
    }
    public void playAlbum(Album album) {
        queueHelper.clearAndAddAlbum(album);
    }

    public void processPlayPause() {
        if(isPlaying()) {
            processPause();
        } else {
            processPlay();
        }
    }

    public void processSetQueueAndPlay(List<Song> queue) {
        queueHelper.clearAndAdd(queue);
        playSong();
    }

    public void processSkipToQueuePosition(int position) {
        queueHelper.setCurrentPosition(position);
        playSong();
    }

    // TODO: use states
    public void processPlay() {
        if(isPlaying()) {
            return;
        } else {
            startPlayer();
        }
    }
    public void processPause(boolean keepFocus) {
        if(!isPlaying()) {
            Log.d("player", "processPause while not playing");
            return;
        } else {
            pausePlayer(keepFocus);
        }
    }
    public void processPause() {
        processPause(false);
    }
    public void processPrev() {
        if(isPlaying() && player.getCurrentPosition() > player.getDuration() * 2 / 100 && getCurrent().getLength() > 4) {
            processSeekTo(0);
        } else if(queueHelper.prev()) {
            playSong();
        } else if(playMode == PlayMode.RepeatAll) {
            queueHelper.skip(-1);
            playSong();
        } else {
            Toast.makeText(getApplicationContext(), "There is no previous song.", Toast.LENGTH_SHORT).show();
        }
    }
    public void processNext() {
        if(queueHelper.next()) {
            playSong();
        }
        else if (playMode == PlayMode.RepeatAll) {
            queueHelper.setCurrentPosition(0);
            playSong();
        } else {
            Toast.makeText(getApplicationContext(), "There is no next song.", Toast.LENGTH_SHORT).show();
        }
    }

    private void pausePlayer(boolean keepFocus) {
        player.pause();
        if(!keepFocus) {
            Log.d("player", "we don't want to keep focus");
            audioManager.abandonAudioFocus(this);
        }
        state = State.Paused;
        updateMediaSessionState();
        buildNotification(generateAction(R.drawable.ic_bigplay, "Play", ACTION_PLAY));
    }

    private void stopPlayer() {
        player.reset();
        audioManager.abandonAudioFocus(this);
        state = State.Stopped;
        updateMediaSessionState();
        updateMediaSessionMetadata();
        queueHelper.clearQueue();
        notificationManager.cancel(NOTIFY_ID);
    }

    private void releasePlayer() {
        stopPlayer();

        player.release();
        player = null;
    }

    private void startPlayer() {
        Log.d("player", "startPlayer");
        requestAudioFocus();

        if(audioFocus == AudioFocus.NoFocusNoDuck) {
            Log.i("player", "No audio focus");
            pauseReason = PauseReason.FocusLoss;
            return;
        } else if(audioFocus == AudioFocus.NoFocusCanDuck) {
            player.setVolume(DUCK_VOLUME, DUCK_VOLUME);
        } else {
            player.setVolume(1.0f,1.0f);
        }

        if(queueHelper.getCurrent() != null) {
            if(state == State.Stopped) {
                playSong();
                return;
            }

            player.start();
            state = State.Playing;
            buildNotification(generateAction(R.drawable.ic_bigpause, "Pause", ACTION_PAUSE));
            updateMediaSessionState();
        }
        else {
            queueHelper.addAllSongs();
        }
    }



    private void playSong() {
        player.reset();

        Song song = queueHelper.getCurrent();

        Config config = new Config(getApplicationContext());
        String endpoint = config.getApiUrl() + "/" + song.getId() + "/play?jwt-token=" + user.getToken();
        Uri uri = Uri.parse(endpoint);

        //Uri uri = mediaStore.launchProxyAndGetSongUri(song);

        try {
            player.setDataSource(getApplicationContext(), uri);
            player.prepareAsync();
            state = State.Preparing;

            updateMediaSessionMetadata();
            updateMediaSessionState();
            buildNotification(generateAction(R.drawable.ic_bigpause, "Pause", ACTION_PAUSE));

        } catch(IOException e) {
            Toast.makeText(getApplicationContext(), "Error (650)", Toast.LENGTH_SHORT).show();
            Log.e("playerService", e.getMessage());
        }
    }

    private void nextSong() {
        if(queueHelper.next()) {
            playSong();
        } else if (playMode == PlayMode.RepeatOne) {
            playSong();
        } else if (playMode == PlayMode.RepeatAll) {
            queueHelper.setCurrentPosition(0);
            playSong();
        } else {
            pausePlayer(false);
        }
    }

    private void updateMediaSessionMetadata() {
        final Song currentSong = queueHelper.getCurrent();

        mediaSession.setMetadata(convertSongToMediaMetadata(currentSong));

        // TODO: album cover
        /*
        Picasso.with(this).load(currentSong.getAlbum().getCoverUri()).into(new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap);
                mediaSession.setMetadata(builder.build());
            }

            @Override
            public void onBitmapFailed(Drawable errorDrawable) {

            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {

            }
        });*/
    }
    private void updateMediaSessionState() {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder();

        int playbackState;
        switch(state) {
            case Playing:
                playbackState = PlaybackStateCompat.STATE_PLAYING;
                break;

            case Paused:
                playbackState = PlaybackStateCompat.STATE_PAUSED;
                break;

            case Stopped:
                playbackState = PlaybackStateCompat.STATE_STOPPED;
                break;

            case Preparing:
                playbackState = PlaybackStateCompat.STATE_BUFFERING;
                break;

            case Buffering:
                playbackState = PlaybackStateCompat.STATE_BUFFERING;
                break;

            default:
                playbackState = PlaybackStateCompat.STATE_NONE;
        }

        PlaybackStateCompat playbackStateCompat = builder.setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS).setState(playbackState, player.getCurrentPosition(), 1.0f).build();
        mediaSession.setPlaybackState(playbackStateCompat);
    }

    private MediaMetadataCompat convertSongToMediaMetadata(Song song) {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();

        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.getAlbum().getArtist().getName());
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.getAlbum().getName());
        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle());
        builder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, song.getTrack());
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.getLengthMs());
        builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.getId());

        return builder.build();
    }

    public PlayMode getPlayMode() {
        return playMode;
    }
    public void setPlayMode(PlayMode playMode) {
        this.playMode = playMode;
    }
}
