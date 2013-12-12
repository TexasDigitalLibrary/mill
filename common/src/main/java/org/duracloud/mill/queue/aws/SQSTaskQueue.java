/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.queue.aws;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.ReceiptHandleIsInvalidException;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.duracloud.mill.domain.Task;
import org.duracloud.mill.queue.TaskNotFoundException;
import org.duracloud.mill.queue.TaskQueue;
import org.duracloud.mill.queue.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * SQSTaskQueue acts as the interface for interacting with an Amazon
 * Simple Queue Service (SQS) queue.
 * This class provides a way to interact with a remote SQS Queue, it
 * emulates the functionality of a queue.
 * @author Erik Paulsson
 *         Date: 10/21/13
 */
public class SQSTaskQueue implements TaskQueue {
    private static Logger log = LoggerFactory.getLogger(SQSTaskQueue.class);

    private AmazonSQSClient sqsClient;
    private String queueName;
    private String queueUrl;
    private Integer visibilityTimeout;  // in seconds

    public enum MsgProp {
        MSG_ID, RECEIPT_HANDLE;
    }

    /**
     * Creates a SQSTaskQueue that serves as a handle to interacting with a remote
     * Amazon SQS Queue.
     * The AmazonSQSClient will search for Amazon credentials on the system as
     * described here:
     * http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/sqs/AmazonSQSClient.html#AmazonSQSClient()
     */
    public SQSTaskQueue(String queueName) {
        this(new AmazonSQSClient(), queueName);
    }

    public SQSTaskQueue(AmazonSQSClient sqsClient, String queueName) {
        this.sqsClient = sqsClient;
        this.queueName = queueName;
        this.queueUrl = getQueueUrl();
        this.visibilityTimeout = getVisibilityTimeout();
    }

    protected Task marshallTask(Message msg) {
        Properties props = new Properties();
        Task task = null;
        try {
            props.load(new StringReader(msg.getBody()));

            if(props.containsKey(Task.KEY_TYPE)) {
                task = new Task();
                for(final String key: props.stringPropertyNames()) {
                    if(key.equals(Task.KEY_TYPE)) {
                        task.setType(Task.Type.valueOf(props.getProperty(key)));
                    } else {
                        task.addProperty(key, props.getProperty(key));
                    }
                }
                task.addProperty(MsgProp.MSG_ID.name(), msg.getMessageId());
                task.addProperty(MsgProp.RECEIPT_HANDLE.name(), msg.getReceiptHandle());
            } else {
                log.error("SQS message from queue: "+ queueName+", queueUrl: " +
                              queueUrl +" does not contain a 'task type'");
            }
        } catch(IOException ioe) {
            log.error("Error creating Task", ioe);
        }
        return task;
    }

    protected String unmarshallTask(Task task) {
        Properties props = new Properties();
        props.setProperty(Task.KEY_TYPE, task.getType().name());
        for(String key: task.getProperties().keySet()) {
            props.setProperty(key, task.getProperty(key));
        }
        StringWriter sw = new StringWriter();
        String msgBody = null;
        try {
            props.store(sw, null);
            msgBody = sw.toString();
        } catch(IOException ioe) {
            log.error("Error unmarshalling Task, queue: " + queueName +
                          ", msgBody: " + msgBody, ioe);
        }
        return msgBody;
    }

    @Override
    public void put(Task task) {
        String msgBody = unmarshallTask(task);
        sqsClient.sendMessage(new SendMessageRequest(queueUrl, msgBody));
        log.info("SQS message successfully placed {} on queue - queue: {}",
                task, queueName);
    }

    /**
     * Convenience method that calls put(Set<Task>)
     * @param tasks
     */
    @Override
    public void put(Task... tasks) {
        Set<Task> taskSet = new HashSet<>();
        taskSet.addAll(Arrays.asList(tasks));
        this.put(taskSet);
    }

    /**
     * Puts multiple tasks on the queue using batch puts.  The tasks argument
     * can contain more than 10 Tasks, in that case there will be multiple SQS
     * batch send requests made each containing up to 10 messages.
     * @param tasks
     */
    @Override
    public void put(Set<Task> tasks) {
        String msgBody = null;
        SendMessageBatchRequestEntry msgEntry = null;
        Set<SendMessageBatchRequestEntry> msgEntries = new HashSet<>();
        for(Task task: tasks) {
            msgBody = unmarshallTask(task);
            msgEntry = new SendMessageBatchRequestEntry()
                .withMessageBody(msgBody)
                .withId(msgEntries.size()+"");  // must set unique ID for each msg in the batch request
            msgEntries.add(msgEntry);

            // Can only send batch of max 10 messages in a SQS queue request
            if(msgEntries.size() == 10) {
                this.sendBatchMessages(msgEntries);
                msgEntries.clear();  // clear the already sent messages
            }
        }

        // After for loop check to see if there are msgs in msgEntries that
        // haven't been sent yet because the size never reached 10.
        if(! msgEntries.isEmpty()) {
            this.sendBatchMessages(msgEntries);
        }
    }

    private void sendBatchMessages(Set<SendMessageBatchRequestEntry> msgEntries) {
        SendMessageBatchRequest sendMessageBatchRequest = new SendMessageBatchRequest()
            .withQueueUrl(queueUrl)
            .withEntries(msgEntries);
        sqsClient.sendMessageBatch(sendMessageBatchRequest);
        log.info("{} SQS messages successfully placed on queue - queue: {}",
                 msgEntries.size(), queueName);
    }

    @Override
    public Task take() throws TimeoutException {
        ReceiveMessageResult result = sqsClient.receiveMessage(
            new ReceiveMessageRequest()
                .withQueueUrl(queueUrl)
                .withMaxNumberOfMessages(1)
                .withAttributeNames("SentTimestamp", "ApproximateReceiveCount"));
        if(result.getMessages() != null && result.getMessages().size() > 0) {
            Message msg = result.getMessages().get(0);

            // The Amazon docs claim this attribute is 'returned as an integer
            // representing the epoch time in milliseconds.'
            // http://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/Query_QueryReceiveMessage.html
            try {
                Long sentTime = Long.parseLong(msg.getAttributes().get("SentTimestamp"));
                Long preworkQueueTime = System.currentTimeMillis() - sentTime;
                log.info("SQS message received - queue: {}, queueUrl: {}, msgId: {}," +
                             " preworkQueueTime: {}, receiveCount: {}"
                    , queueName, queueUrl, msg.getMessageId()
                    , DurationFormatUtils.formatDuration(preworkQueueTime, "HH:mm:ss,SSS")
                    , msg.getAttributes().get("ApproximateReceiveCount"));
            } catch(NumberFormatException nfe) {
                log.error("Error converting 'SentTimestamp' SQS message" +
                              " attribute to Long, messageId: " +
                              msg.getMessageId(), nfe);
            }

            Task task = marshallTask(msg);
            task.setVisibilityTimeout(visibilityTimeout);
            return task;
        } else {
            throw new TimeoutException("No tasks available from queue: " +
                                           queueName + ", queueUrl: " + queueUrl);
        }
    }

    @Override
    public void extendVisibilityTimeout(Task task) throws TaskNotFoundException {
        try {
            sqsClient.changeMessageVisibility(new ChangeMessageVisibilityRequest()
                                                  .withQueueUrl(queueUrl)
                                                  .withReceiptHandle(task.getProperty(MsgProp.RECEIPT_HANDLE.name()))
                                                  .withVisibilityTimeout(task.getVisibilityTimeout()));
            log.info("extended visibility timeout {} seconds for {}",
                    task.getVisibilityTimeout(), task);
        } catch(ReceiptHandleIsInvalidException rhe) {
            log.error("failed to extend visibility timeout on task " + task
                    + ": " + rhe.getMessage(), rhe);

            throw new TaskNotFoundException(rhe);
        }
    }

    @Override
    public void deleteTask(Task task) throws TaskNotFoundException {
        try {
            sqsClient.deleteMessage(new DeleteMessageRequest()
                    .withQueueUrl(queueUrl)
                    .withReceiptHandle(
                        task.getProperty(MsgProp.RECEIPT_HANDLE.name())));
            log.info("successfully deleted {}", task);

        } catch(ReceiptHandleIsInvalidException rhe) {
            log.error(
                    "failed to delete task " + task + ": " + rhe.getMessage(),
                    rhe);

            throw new TaskNotFoundException(rhe);
        }
    }
    
    /* (non-Javadoc)
     * @see org.duracloud.mill.queue.TaskQueue#requeue(org.duracloud.mill.domain.Task)
     */
    @Override
    public void requeue(Task task) {
        int attempts = task.getAttempts();
        task.incrementAttempts();
        try {
            deleteTask(task);
        } catch (TaskNotFoundException e) {
            log.error("unable to delete " + task+ " ignoring - requeuing anyway");
        }

        put(task);
        log.warn("requeued {} after {} failed attempts.", task, attempts);
        
    }

    @Override
    public Integer size() {
        GetQueueAttributesResult result = queryQueueAttributes(QueueAttributeName.ApproximateNumberOfMessages);
        String sizeStr = result.getAttributes().get(QueueAttributeName.ApproximateNumberOfMessages.name());
        Integer size = Integer.parseInt(sizeStr);
        return size;
    }

    private Integer getVisibilityTimeout() {
        GetQueueAttributesResult result = queryQueueAttributes(QueueAttributeName.VisibilityTimeout);
        String visStr = result.getAttributes().get(QueueAttributeName.VisibilityTimeout.name());
        Integer visibilityTimeout = Integer.parseInt(visStr);
        return visibilityTimeout;
    }

    private String getQueueUrl() {
        return sqsClient.getQueueUrl(
            new GetQueueUrlRequest().withQueueName(queueName)).getQueueUrl();
    }

    private GetQueueAttributesResult queryQueueAttributes(QueueAttributeName... attrNames) {
        return sqsClient.getQueueAttributes(new GetQueueAttributesRequest()
            .withQueueUrl(queueUrl)
            .withAttributeNames(attrNames));
    }

}
