package com.davsinghm.wget.core;

import android.content.Context;
import android.net.Uri;

import com.davsinghm.wget.Constants;
import com.davsinghm.wget.core.info.DownloadInfo;
import com.davsinghm.wget.core.info.State;
import com.davsinghm.wget.core.info.ex.DownloadInterruptedException;
import com.davsinghm.wget.core.io.RandomAccessUri;
import com.davsinghm.wget.core.io.Utils;
import com.davsinghm.wget.core.util.HttpUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class DirectSingle extends Direct {

    public DirectSingle(Context context, DownloadInfo info, Uri directory, String filename) {
        super(context, info, directory, filename);
    }

    void downloadPart(DownloadInfo info, AtomicBoolean stop, Runnable notify) throws IOException {

        RandomAccessUri randomAccessUri = null;
        BufferedInputStream bufferedInputStream = null;

        try {
            info.setCount(0);
            info.getSpeedInfo().start(0);

            randomAccessUri = Utils.openUriFile(getContext(), getTargetFile().getUri(), "rw");
            randomAccessUri.seek(0); //TODO see if needed

            HttpURLConnection urlConnection = HttpUtils.openConnection(info, info.getCount());
            HttpUtils.checkResponse(urlConnection);
            bufferedInputStream = new BufferedInputStream(urlConnection.getInputStream());

            byte[] bytes = new byte[Constants.BUF_SIZE];
            int read;
            while ((read = bufferedInputStream.read(bytes)) > 0) {
                randomAccessUri.write(bytes, 0, read);

                info.setCount(info.getCount() + read);
                notify.run();
                info.updateSpeed();

                if (stop.get())
                    throw new DownloadInterruptedException("Stopped");
                if (Thread.interrupted())
                    throw new DownloadInterruptedException("Interrupted");
            }

        } catch (InterruptedIOException e) {
            throw new DownloadInterruptedException("Interrupted", e);
        } finally {
            if (randomAccessUri != null)
                randomAccessUri.close();
            if (bufferedInputStream != null)
                bufferedInputStream.close();
        }
    }

    @Override
    public void download(final AtomicBoolean stop, final Runnable notify) {

        try {
            RetryWrap.run(stop, new RetryWrap.Wrap() {

                @Override
                public Context getContext() {
                    return DirectSingle.this.getContext();
                }

                @Override
                public Object download() throws IOException {
                    getInfo().setState(State.DOWNLOADING);
                    notify.run();

                    downloadPart(getInfo(), stop, notify);
                    return null;
                }

                @Override
                public void retry(int delay, Throwable e) {
                    getInfo().setDelay(delay, e);
                    notify.run();
                }

                @Override
                public void moved(URL url) {
                    getInfo().setState(State.RETRYING);
                    notify.run();
                }
            });

            getInfo().setState(State.DONE);
            notify.run();
        } catch (DownloadInterruptedException e) {
            getInfo().setState(State.STOP);
            notify.run();

            throw e;
        } catch (RuntimeException e) {
            getInfo().setState(State.ERROR);
            notify.run();

            throw e;
        }
    }

    /**
     * check existing file for download resume. for single download it will
     * check file dose not exist or zero size. so we can resume download.
     *
     * @param info
     * @param targetFile
     * @return return true - if all ok, false - if download can not be restored.
     */
    public static boolean canResume(DownloadInfo info, File targetFile) {
        if (info.getCount() != 0)
            return false;

        if (targetFile.exists()) {
            if (targetFile.length() != 0)
                return false;
        }

        return true;
    }
}
