package fr.hostux.louis.koelouis.fragments;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.LinkedList;
import java.util.List;

import fr.hostux.louis.koelouis.R;
import fr.hostux.louis.koelouis.helper.QueueHelper;
import fr.hostux.louis.koelouis.models.Song;

public class QueueFragment extends Fragment {

    private static final String ARG_COLUMN_COUNT = "column-count";
    private int columnCount = 1;
    private OnListFragmentInteractionListener listener;
    private QueueRecyclerViewAdapter adapter;

    public QueueFragment() {
    }

    @SuppressWarnings("unused")
    public static QueueFragment newInstance(int columnCount) {
        QueueFragment fragment = new QueueFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_COLUMN_COUNT, columnCount);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            columnCount = getArguments().getInt(ARG_COLUMN_COUNT);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_queue_list, container, false);

        if (view instanceof RecyclerView) {
            Context context = view.getContext();
            RecyclerView recyclerView = (RecyclerView) view;
            if (columnCount <= 1) {
                recyclerView.setLayoutManager(new LinearLayoutManager(context));
            } else {
                recyclerView.setLayoutManager(new GridLayoutManager(context, columnCount));
            }

            List<MediaSessionCompat.QueueItem> songs = getActivity().getSupportMediaController().getQueue();

            adapter = new QueueRecyclerViewAdapter(getContext(), songs, listener);
            recyclerView.setAdapter(adapter);
        }

        if(listener != null) {
            listener.updateActivityTitle("Current queue");
        }

        return view;
    }

    public interface OnListFragmentInteractionListener {
        void updateActivityTitle(String title);
        void onListFragmentInteraction(Song song, int position);
        void onPopupButtonClick(Song song, View view, int position);
    }

    public void setListener(OnListFragmentInteractionListener listener) {
        this.listener = listener;
    }
}
