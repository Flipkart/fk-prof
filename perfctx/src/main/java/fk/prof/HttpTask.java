package fk.prof;

public class HttpTask {

    private long startTime, startTs, endTs;

    public AsyncTaskCtx processTask;

    public void startRead() {
        startTime = System.currentTimeMillis();
        startTs = System.nanoTime();
    }

    public void startProcess(String path) {
        processTask = AsyncTaskCtx.newTask("[dispatch] " + path);
    }

    public void endProcess() {
        if(processTask == null) {
            throw new IllegalStateException("dispatchEnd called before dispatch");
        }
        processTask.end();
    }

    public void complete() {
        endTs = System.nanoTime();
        System.out.println(this.toString());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"startTime\": \"" + startTime + "\",");
        sb.append("\"endTsDelta\": " + (endTs - startTs) + ",");
        sb.append("\"handler\": " + processTask.toString());
        sb.append("}");
        return sb.toString();
    }
}
