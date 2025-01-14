package jenkins.plugins.slack.pipeline;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.plugins.slack.HttpClient;
import jenkins.security.MasterToSlaveCallable;
import org.apache.http.HttpEntity;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

public class SlackUploadFileRunner extends MasterToSlaveCallable<Boolean, Throwable> implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String API_URL = "https://slack.com/api/files.upload";
    private static final Logger logger = Logger.getLogger(SlackUploadFileRunner.class.getName());
    private static final String UPLOAD_FAILED_TEMPLATE = "Slack upload may have failed. Response: ";

    private final FilePath filePath;
    private String fileToUploadPath;

    private final String channels;

    private final String token;

    private final TaskListener listener;
    private final String initialComment;

    public SlackUploadFileRunner(TaskListener listener, SlackFileRequest slackFileRequest) {
        this.listener = listener;
        this.filePath = slackFileRequest.getFilePath();
        this.fileToUploadPath = slackFileRequest.getFileToUploadPath();
        this.channels = slackFileRequest.getChannels();
        this.initialComment = slackFileRequest.getInitialComment();
        this.token = slackFileRequest.getToken();
    }

    @Override
    public Boolean call() throws Throwable {
        logger.info(filePath + "");
        logger.info(fileToUploadPath);
        listener.getLogger().println(String.format("Using dirname=%s and includeMask=%s", filePath.getRemote(), fileToUploadPath));

        final List<File> files = new ArrayList<>();
        new DirScanner.Glob(fileToUploadPath, null).scan(new File(filePath.getRemote()), new FileVisitor() {
            @Override
            public void visit(File file, String relativePath) {
                if (file.isFile()) {
                    listener.getLogger().println("Adding file " + file.getAbsolutePath());
                    files.add(file);
                }
            }
        });

        if (files.isEmpty()) {
            listener.getLogger().println("No files found for mask=" + this.filePath);
            return false;
        }

        return doIt(files);
    }

    private boolean doIt(List<File> files) {
            CloseableHttpClient client = HttpClient.getCloseableHttpClient();
            String threadTs = null;
            String theChannels = channels;

            //thread_ts is passed once with roomId: Ex: roomId:threadTs
            String[] splitThread = channels.split(":", 2);
            if (splitThread.length == 2) {
                theChannels = splitThread[0];
                threadTs = splitThread[1];
            }
            for (File file:files) {
                 MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create()
                        .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                        .addTextBody("channels", theChannels, ContentType.DEFAULT_TEXT)
                        .addBinaryBody("file", file, ContentType.DEFAULT_BINARY, file.getName());

                if (initialComment != null) {
                   multipartEntityBuilder = multipartEntityBuilder
                           .addTextBody("initial_comment", initialComment, ContentType.DEFAULT_TEXT);
                }

                if (threadTs != null) {
                    multipartEntityBuilder = multipartEntityBuilder
                            .addTextBody("thread_ts", threadTs, ContentType.DEFAULT_TEXT);
                }

                HttpUriRequest request = RequestBuilder
                        .post(API_URL)
                        .setEntity(multipartEntityBuilder.build())
                        .addHeader("Authorization", "Bearer " + token)
                        .build();
                ResponseHandler<JSONObject> responseHandler = response -> {
                    int status = response.getStatusLine().getStatusCode();
                    if (status >= 200 && status < 300) {
                        HttpEntity entity = response.getEntity();
                        return entity != null ? new org.json.JSONObject(EntityUtils.toString(entity)) : null;
                    } else {
                        logger.log(Level.WARNING, UPLOAD_FAILED_TEMPLATE + status);
                        return null;
                    }
                };
                try {
                    org.json.JSONObject responseBody = client.execute(request, responseHandler);
                    if (responseBody != null && !responseBody.getBoolean("ok")) {
                        listener.getLogger().println(UPLOAD_FAILED_TEMPLATE + responseBody.toString());
                        return false;
                    }
                } catch (IOException e) {
                    String msg = "Exception uploading files '" + file + "' to Slack ";
                    logger.log(Level.WARNING, msg, e);
                    listener.getLogger().println(msg + e.getMessage());
                }
            }
        return true;
    }
}
