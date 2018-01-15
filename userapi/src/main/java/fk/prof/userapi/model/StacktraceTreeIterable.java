package fk.prof.userapi.model;

import fk.prof.idl.Profile;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @author gaurav.ashok
 */
public class StacktraceTreeIterable implements Iterable<Profile.FrameNode> {

    private List<Profile.FrameNodeList> stackTraceParts;

    public StacktraceTreeIterable(List<Profile.FrameNodeList> stackTraceParts) {
        this.stackTraceParts = stackTraceParts;
    }

    @Override
    public Iterator<Profile.FrameNode> iterator() {
        return new FrameNodeIterator();
    }

    class FrameNodeIterator implements Iterator<Profile.FrameNode> {

        int partIndex = 0;
        int offsetWithinPart = 0;

        @Override
        public boolean hasNext() {
            return partIndex < stackTraceParts.size() && offsetWithinPart < stackTraceParts.get(partIndex).getFrameNodesCount();
        }

        @Override
        public Profile.FrameNode next() {
            if(!hasNext()) {
                throw new NoSuchElementException();
            }

            Profile.FrameNode result = stackTraceParts.get(partIndex).getFrameNodes(offsetWithinPart);

            ++offsetWithinPart;
            if(offsetWithinPart >= stackTraceParts.get(partIndex).getFrameNodesCount()) {
                offsetWithinPart = 0;
                ++partIndex;
            }

            return result;
        }
    }
}
