package net.adamsmolnik.handler;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;

import net.adamsmolnik.handler.exception.UploadPhotoHandlerException;
import net.adamsmolnik.handler.model.ImageMetadata;
import net.adamsmolnik.util.ImageMetadataExplorer;
import net.adamsmolnik.util.ImageResizer;
import net.adamsmolnik.util.ResizerResult;

/**
 * A simplified, single-threaded variation of multithreaded UploadPhotoHandler
 * meant for codepot.pl workshop's purposes.
 * 
 * @author asmolnik
 *
 */
public class UploadPhotoHandler {

	private static final String STUDENT_PREFIX = "001";

	private static final String PHOTOS_TABLE_NAME = STUDENT_PREFIX + "-codepot-photos";

	private static final String DEST_BUCKET = STUDENT_PREFIX + "-codepot-photos";

	private static final String KEY_PREFIX = "photos/";

	private static final String JPEG_EXT = "jpg";

	private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-A");

	private static final int THUMBNAIL_SIZE = 300;

	private static final int WEB_IMAGE_SIZE = 1080;

	public void handle(S3Event s3Event, Context context) {
		LambdaLogger log = context.getLogger();
		s3Event.getRecords().forEach(record -> {
			try {
				process(new S3ObjectStream(record.getS3(), record.getUserIdentity()), log);
			} catch (IOException e) {
				throw new UploadPhotoHandlerException(e);
			}
		});
	}

	private void process(S3ObjectStream os, LambdaLogger log) throws IOException {
		String srcKey = os.getKey();
		log.log("File uploaded: " + os.getKey());
		String userId = os.getUserId();
		String userKeyPrefix = KEY_PREFIX + userId + "/";
		ImageMetadata imd = new ImageMetadataExplorer().explore(os.newCachedInputStream());
		ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(imd.getPhotoTaken().getTime()), ZoneId.of("UTC"));
		String baseDestKey = createDestKey(srcKey, zdt, JPEG_EXT);
		String photoKey = userKeyPrefix + baseDestKey;
		String thumbnailKey = userKeyPrefix + "thumbnails/" + baseDestKey;
		AmazonS3 s3 = new AmazonS3Client();
		putS3Object(photoKey, new ImageResizer(os.newCachedInputStream(), WEB_IMAGE_SIZE).resize(), s3);
		putS3Object(thumbnailKey, new ImageResizer(os.newCachedInputStream(), THUMBNAIL_SIZE).resize(), s3);

		PutRequest pr = new PutRequest().withUserId(userId).withPrincipalId(os.getPrincipalId()).withPhotoKey(photoKey).withThumbnailKey(thumbnailKey)
				.withZonedDateTime(zdt).withImageMetadata(imd);
		new AmazonDynamoDBClient().putItem(createPutRequest(pr));
	}

	private void putS3Object(String objectKey, ResizerResult rr, AmazonS3 s3) {
		ObjectMetadata md = new ObjectMetadata();
		md.setContentLength(rr.getSize());
		s3.putObject(DEST_BUCKET, objectKey, rr.getInputStream(), md);
	}

	private PutItemRequest createPutRequest(PutRequest pr) {
		return new PutItemRequest().withTableName(PHOTOS_TABLE_NAME).addItemEntry("userId", new AttributeValue(pr.userId))
				.addItemEntry("photoTakenDate", new AttributeValue(pr.zdt.format(DateTimeFormatter.ISO_LOCAL_DATE)))
				.addItemEntry("photoTakenTime", new AttributeValue(pr.zdt.format(DateTimeFormatter.ISO_LOCAL_TIME)))
				.addItemEntry("photoKey", new AttributeValue(pr.photoKey)).addItemEntry("thumbnailKey", new AttributeValue(pr.thumbnailKey))
				.addItemEntry("bucket", new AttributeValue(DEST_BUCKET)).addItemEntry("madeBy", new AttributeValue(pr.imd.getMadeBy()))
				.addItemEntry("model", new AttributeValue(pr.imd.getModel())).addItemEntry("principalId", new AttributeValue(pr.principalId));
	}

	private String createDestKey(String srcKey, ZonedDateTime zdt, String ext) {
		return zdt.format(DT_FORMATTER) + "-" + Integer.toHexString(UUID.randomUUID().toString().hashCode()) + "." + ext;
	}

}