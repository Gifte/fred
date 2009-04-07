package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableInsert;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.NativeThread;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class SplitFileInserterSegment extends SendableInsert implements FECCallback, Encodeable {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
			}
		});
	}
	
	final SplitFileInserter parent;
	final BaseClientPutter putter;

	final short splitfileAlgo;

	final Bucket[] dataBlocks;

	final Bucket[] checkBlocks;

	final ClientCHK[] dataURIs;

	final ClientCHK[] checkURIs;

	final int[] dataRetries;
	final int[] checkRetries;
	
	final int[] dataConsecutiveRNFs;
	final int[] checkConsecutiveRNFs;
	
	/** Block numbers not finished */
	final ArrayList<Integer> blocks;
	
	final boolean[] dataFinished;
	final boolean[] checkFinished;
	
	final boolean[] dataFailed;
	final boolean[] checkFailed;
	
	final int maxRetries;
	
	final InsertContext blockInsertContext;

	final int segNo;

	private volatile boolean encoded;
	
	private volatile boolean started;
	
	private volatile boolean finished;
	
	private volatile boolean hasURIs;

	private final boolean getCHKOnly;

	private InsertException toThrow;

	private final FailureCodeTracker errors;

	private int blocksGotURI;
	private int blocksSucceeded;
	private int blocksCompleted;
	
	private final boolean persistent;
	
	private FECJob encodeJob;
	

	public SplitFileInserterSegment(SplitFileInserter parent, boolean persistent, BaseClientPutter putter,
			short splitfileAlgo, int checkBlockCount, Bucket[] origDataBlocks,
			InsertContext blockInsertContext, boolean getCHKOnly, int segNo, ObjectContainer container) {
		super(persistent);
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.persistent = persistent;
		this.errors = new FailureCodeTracker(true);
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		this.dataBlocks = origDataBlocks;
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new ClientCHK[checkBlockCount];
		dataURIs = new ClientCHK[origDataBlocks.length];
		dataRetries = new int[origDataBlocks.length];
		checkRetries = new int[checkBlockCount];
		dataFinished = new boolean[origDataBlocks.length];
		checkFinished = new boolean[checkBlockCount];
		dataFailed = new boolean[origDataBlocks.length];
		checkFailed = new boolean[checkBlockCount];
		dataConsecutiveRNFs = new int[origDataBlocks.length];
		checkConsecutiveRNFs = new int[checkBlockCount];
		blocks = new ArrayList<Integer>();
		putter.addBlocks(dataURIs.length + checkURIs.length, container);
		putter.addMustSucceedBlocks(dataURIs.length + checkURIs.length, container);
		this.segNo = segNo;
		if(persistent) container.activate(blockInsertContext, 1);
		maxRetries = blockInsertContext.maxInsertRetries;
		this.putter = putter;
		
	}

	/**
	 * Resume an insert segment
	 * 
	 * @throws ResumeException
	 */
	public SplitFileInserterSegment(SplitFileInserter parent, boolean persistent, BaseClientPutter putter,
			SimpleFieldSet fs, short splitfileAlgorithm, InsertContext ctx,
			boolean getCHKOnly, int segNo, ClientContext context, ObjectContainer container) throws ResumeException {
		super(persistent);
		this.parent = parent;
		this.splitfileAlgo = splitfileAlgorithm;
		this.getCHKOnly = getCHKOnly;
		this.persistent = persistent;
		this.blockInsertContext = ctx;
		this.maxRetries = ctx.maxInsertRetries;
		this.segNo = segNo;
		if (!"SplitFileInserterSegment".equals(fs.get("Type")))
			throw new ResumeException("Wrong Type: " + fs.get("Type"));
		finished = Fields.stringToBool(fs.get("Finished"), false);
		encoded = true;
		started = Fields.stringToBool(fs.get("Started"), false);
		SimpleFieldSet errorsFS = fs.subset("Errors");
		if (errorsFS != null)
			this.errors = new FailureCodeTracker(true, errorsFS);
		else
			this.errors = new FailureCodeTracker(true);
		if (finished && !errors.isEmpty())
			toThrow = InsertException.construct(errors);
		blocksGotURI = 0;
		blocksCompleted = 0;
		SimpleFieldSet dataFS = fs.subset("DataBlocks");
		if (dataFS == null)
			throw new ResumeException("No data blocks");
		String tmp = dataFS.get("Count");
		if (tmp == null)
			throw new ResumeException("No data block count");
		int dataBlockCount;
		try {
			dataBlockCount = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt data blocks count: " + e + " : "
					+ tmp);
		}

		hasURIs = true;

		dataBlocks = new Bucket[dataBlockCount];
		dataURIs = new ClientCHK[dataBlockCount];
		dataRetries = new int[dataBlockCount];
		dataConsecutiveRNFs = new int[dataBlockCount];
		dataFinished = new boolean[dataBlockCount];
		dataFailed = new boolean[dataBlockCount];
		blocks = new ArrayList<Integer>();
		this.putter = putter;

		// Check blocks first, because if there are missing check blocks, we
		// need
		// all the data blocks so we can re-encode.

		SimpleFieldSet checkFS = fs.subset("CheckBlocks");
		if (checkFS != null) {
			tmp = checkFS.get("Count");
			if (tmp == null)
				throw new ResumeException(
						"Check blocks but no check block count");
			int checkBlockCount;
			try {
				checkBlockCount = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				throw new ResumeException("Corrupt check blocks count: " + e
						+ " : " + tmp);
			}
			checkBlocks = new Bucket[checkBlockCount];
			checkURIs = new ClientCHK[checkBlockCount];
			checkRetries = new int[checkBlockCount];
			checkConsecutiveRNFs = new int[checkBlockCount];
			checkFinished = new boolean[checkBlockCount];
			checkFailed = new boolean[checkBlockCount];
			for (int i = 0; i < checkBlockCount; i++) {
				String index = Integer.toString(i);
				SimpleFieldSet blockFS = checkFS.subset(index);
				if (blockFS == null) {
					hasURIs = false;
					encoded = false;
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					continue;
				}
				tmp = blockFS.get("URI");
				if (tmp != null) {
					try {
						checkURIs[i] = (ClientCHK) ClientKey
								.getBaseKey(new FreenetURI(tmp));
						blocksGotURI++;
					} catch (MalformedURLException e) {
						throw new ResumeException("Corrupt URI: " + e + " : "
								+ tmp);
					}
				} else {
					hasURIs = false;
				}
				boolean blockFinished = Fields.stringToBool(blockFS
						.get("Finished"), false)
						&& checkURIs[i] != null;
				if (blockFinished && checkURIs[i] == null) {
					Logger.error(this, "No URI for check block " + i + " of "
							+ segNo + " yet apparently finished?");
					encoded = false;
				}
				// Read data; only necessary if the block isn't finished.
				if (!blockFinished) {
					SimpleFieldSet bucketFS = blockFS.subset("Data");
					if (bucketFS != null) {
						try {
							checkBlocks[i] = SerializableToFieldSetBucketUtil
									.create(bucketFS, context.random,
											ctx.persistentFileTracker);
							if (logMINOR)
								Logger.minor(this, "Check block " + i + " : "
										+ checkBlocks[i]);
						} catch (CannotCreateFromFieldSetException e) {
							Logger.error(this,
									"Failed to deserialize check block " + i
											+ " of " + segNo + " : " + e, e);
							// Re-encode it.
							checkBlocks[i] = null;
							encoded = false;
						}
						if (checkBlocks[i] == null)
							throw new ResumeException(
									"Check block "
											+ i
											+ " of "
											+ segNo
											+ " not finished but no data (create returned null)");
					}
					// Don't create fetcher yet; that happens in start()
				} else
					blocksCompleted++;
				if (checkBlocks[i] == null && checkURIs[i] == null) {
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					encoded = false;
				}
				checkFS.removeSubset(index);
			}
			
			if(checkBlocks.length > dataBlocks.length) {
				// Work around 1135 bug.
				// FIXME remove
				throw new ResumeException("Detected 1135 insert bug, you must restart the insert");
			}
		} else {
			Logger.normal(this, "Not encoded because no check blocks");
			encoded = false;
			FECCodec splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm,
					dataBlockCount);
			int checkBlocksCount =splitfileAlgo.countCheckBlocks();
			this.checkURIs = new ClientCHK[checkBlocksCount];
			this.checkBlocks = new Bucket[checkBlocksCount];
			checkRetries = new int[checkBlocksCount];
			checkConsecutiveRNFs = new int[checkBlocksCount];
			checkFinished = new boolean[checkBlocksCount];
			checkFailed = new boolean[checkBlocksCount];
			hasURIs = false;
		}

		for (int i = 0; i < dataBlockCount; i++) {
			String index = Integer.toString(i);
			SimpleFieldSet blockFS = dataFS.subset(index);
			if (blockFS == null)
				throw new ResumeException("No data block " + i + " on segment "
						+ segNo);
			tmp = blockFS.get("URI");
			if (tmp != null) {
				try {
					dataURIs[i] = (ClientCHK) ClientKey
							.getBaseKey(new FreenetURI(tmp));
					blocksGotURI++;
				} catch (MalformedURLException e) {
					throw new ResumeException("Corrupt URI: " + e + " : " + tmp);
				}
			} else
				hasURIs = false;
			boolean blockFinished = Fields.stringToBool(
					blockFS.get("Finished"), false);
			if (blockFinished && dataURIs[i] == null)
				throw new ResumeException("Block " + i + " of " + segNo
						+ " finished but no URI");
			if (!blockFinished)
				finished = false;
			else
				blocksCompleted++;

			// Read data
			SimpleFieldSet bucketFS = blockFS.subset("Data");
			if (bucketFS == null) {
				if (!blockFinished)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " not finished but no data");
				else if (splitfileAlgorithm > 0 && !encoded)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " data not available even though not encoded");
			} else {
				try {
					dataBlocks[i] = SerializableToFieldSetBucketUtil.create(
							bucketFS, context.random, ctx.persistentFileTracker);
					if (logMINOR)
						Logger.minor(this, "Data block " + i + " : "
								+ dataBlocks[i]);
				} catch (CannotCreateFromFieldSetException e) {
					throw new ResumeException("Failed to deserialize block "
							+ i + " of " + segNo + " : " + e, e);
				}
				if (dataBlocks[i] == null)
					throw new ResumeException(
							"Block "
									+ i
									+ " of "
									+ segNo
									+ " could not serialize data (create returned null) from "
									+ bucketFS);
				// Don't create fetcher yet; that happens in start()
			}
			dataFS.removeSubset(index);
		}

		if (!encoded) {
			finished = false;
			hasURIs = false;
			for (int i = 0; i < dataBlocks.length; i++)
				if (dataBlocks[i] == null)
					throw new ResumeException("Missing data block " + i
							+ " and need to reconstruct check blocks");
		}
		putter.addBlocks(dataURIs.length + checkURIs.length, container);
		putter.addMustSucceedBlocks(dataURIs.length + checkURIs.length, container);
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		// Always called by parent, so don't activate or deactivate parent.
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
			container.activate(blocks, 2);
		}
		if (logMINOR) {
			if(parent == null) throw new NullPointerException();
			Logger.minor(this, "Starting segment " + segNo + " of " + parent
					+ " (" + parent.dataLength + "): " + this + " ( finished="
					+ finished + " encoded=" + encoded + " hasURIs=" + hasURIs
					+ " persistent=" + persistent + ')');
		}
		boolean fin = true;

		for (int i = 0; i < dataBlocks.length; i++) {
			if (dataBlocks[i] != null) { // else already finished on creation
				fin = false;
				synchronized(this) {
					blocks.add(i);
				}
			} else {
				parent.parent.completedBlock(true, container, context);
			}
		}
		// parent.parent.notifyClients();
		started = true;
		FECJob job = null;
		FECCodec splitfileAlgo = null;
		if (!encoded) {
			if (logMINOR)
				Logger.minor(this, "Segment " + segNo + " of " + parent + " ("
						+ parent.dataLength + ") is not encoded");
			splitfileAlgo = FECCodec.getCodec(this.splitfileAlgo,
					dataBlocks.length, checkBlocks.length);
				if (logMINOR)
					Logger.minor(this, "Encoding segment " + segNo + " of "
							+ parent + " (" + parent.dataLength + ") persistent="+persistent);
				// Encode blocks
				synchronized(this) {
					if(!encoded){
						// FIXME necessary??? the queue is persistence aware, won't it activate them...?
						if(persistent) {
							for(int i=0;i<dataBlocks.length;i++)
								container.activate(dataBlocks[i], 5);
						}
						job = encodeJob = new FECJob(splitfileAlgo, context.fecQueue, dataBlocks, checkBlocks, CHKBlock.DATA_LENGTH, persistent ? blockInsertContext.persistentBucketFactory : context.tempBucketFactory, this, false, parent.parent.getPriorityClass(), persistent);
					}
				}				
				fin = false;
		} else {
			for (int i = 0; i < checkBlocks.length; i++) {
				if (checkBlocks[i] != null) {
					synchronized(this) {
						blocks.add(i + dataBlocks.length);
					}
					fin = false;
				} else
					parent.parent.completedBlock(true, container, context);
			}
			onEncodedSegment(container, context, null, dataBlocks, checkBlocks, null, null);
		}
		if (hasURIs) {
			parent.segmentHasURIs(this, container, context);
		}
		boolean fetchable;
		synchronized (this) {
			fetchable = (blocksCompleted > dataBlocks.length);
		}
		if(persistent) {
			container.store(this);
			container.store(blocks);
		}
		if (fetchable)
			parent.segmentFetchable(this, container);
		if (fin)
			finish(container, context, parent);
		else
			schedule(container, context);
		if (finished) {
			finish(container, context, parent);
		}
		if(job != null) {
			splitfileAlgo.addToQueue(job, context.fecQueue, container);
		}
	}

	private void schedule(ObjectContainer container, ClientContext context) {
		if(!getCHKOnly) {
			this.getScheduler(context).registerInsert(this, persistent, false, container);
		} else {
			tryEncode(container, context);
		}
	}

	public void tryEncode(ObjectContainer container, ClientContext context) {
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataURIs[i] == null && dataBlocks[i] != null) {
				try {
					boolean deactivate = false;
					if(persistent) {
						deactivate = !container.ext().isActive(dataBlocks[i]);
						if(deactivate) container.activate(dataBlocks[i], 1);
					}
					ClientCHK key = (ClientCHK) encodeBucket(dataBlocks[i]).getClientKey();
					if(deactivate) container.deactivate(dataBlocks[i], 1);
					onEncode(i, key, container, context);
				} catch (CHKEncodeException e) {
					fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);						
				} catch (IOException e) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				}
			} else if(dataURIs[i] == null && dataBlocks[i] == null) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, "Data block "+i+" cannot be encoded: no data", null), container, context);					
			}
		}
		if(encoded) {
			for(int i=0;i<checkBlocks.length;i++) {
				if(checkURIs[i] == null && checkBlocks[i] != null) {
					try {
						boolean deactivate = false;
						if(persistent) {
							deactivate = !container.ext().isActive(checkBlocks[i]);
							if(deactivate) container.activate(checkBlocks[i], 1);
						}
						ClientCHK key = (ClientCHK) encodeBucket(checkBlocks[i]).getClientKey();
						if(deactivate) container.deactivate(checkBlocks[i], 1);
						onEncode(i+dataBlocks.length, key, container, context);
					} catch (CHKEncodeException e) {
						fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);						
					} catch (IOException e) {
						fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
					}
				} else if(checkURIs[i] == null && checkBlocks[i] == null) {
					fail(new InsertException(InsertException.INTERNAL_ERROR, "Data block "+i+" cannot be encoded: no data", null), container, context);					
				}
			}
		}
	}

	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {} // irrevelant

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
			container.activate(blocks, 2);
		}
		boolean fin;
		synchronized(this) {
			fin = finished;
			encodeJob = null;
		}
		if(removeOnEncode) {
			if(logMINOR) Logger.minor(this, "Removing on encode: "+this);
			for(int i=0;i<dataBuckets.length;i++) {
				if(dataBuckets[i] == null) continue;
				dataBuckets[i].free();
				if(persistent)
					dataBuckets[i].removeFrom(container);
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				if(checkBuckets[i] == null) continue;
				checkBuckets[i].free();
				if(persistent)
					checkBuckets[i].removeFrom(container);
				checkBuckets[i] = null;
			}
			removeFrom(container, context);
			return;
		}
		if(fin) {
			Logger.error(this, "Encoded segment even though segment finished! Freeing buckets...");
			for(int i=0;i<dataBuckets.length;i++) {
				if(dataBuckets[i] == null) continue;
				dataBuckets[i].free();
				if(persistent)
					dataBuckets[i].removeFrom(container);
				dataBuckets[i] = null;
			}
			for(int i=0;i<checkBuckets.length;i++) {
				if(checkBuckets[i] == null) continue;
				checkBuckets[i].free();
				if(persistent)
					checkBuckets[i].removeFrom(container);
				checkBuckets[i] = null;
			}
			return;
		}
		// Start the inserts
		try {
			if(logMINOR)
				Logger.minor(this, "Scheduling "+checkBlocks.length+" check blocks...");
			for (int i = 0; i < checkBlocks.length; i++) {
				// See comments on FECCallback: WE MUST COPY THE DATA BACK!!!
				checkBlocks[i] = checkBuckets[i];
				if(checkBlocks[i] == null) {
					if(logMINOR)
						Logger.minor(this, "Skipping check block "+i+" - is null");
					continue;
				}
				if(persistent)
					checkBlocks[i].storeTo(container);
				if(persistent) {
					container.deactivate(checkBlocks[i], 1);
				}
			}
			synchronized(this) {
				for(int i=0;i<checkBlocks.length;i++)
					blocks.add(dataBlocks.length + i);
			}
			if(persistent) container.store(blocks);
		} catch (Throwable t) {
			Logger.error(this, "Caught " + t + " while encoding " + this, t);
			InsertException ex = new InsertException(
					InsertException.INTERNAL_ERROR, t, null);
			finish(ex, container, context, parent);
			if(persistent)
				container.deactivate(parent, 1);
			return;
		}

		synchronized (this) {
			encoded = true;
		}
		
		if(persistent) {
			container.store(this);
			container.activate(parent, 1);
		}

		// Tell parent only after have started the inserts.
		// Because of the counting.
		parent.encodedSegment(this, container, context);

		synchronized (this) {
			for (int i = 0; i < dataBlocks.length; i++) {
				if (dataFinished[i] && dataBlocks[i] != null) {
					if(logMINOR) Logger.minor(this, "Freeing data block "+i+" delayed for encode");
					if(persistent) container.activate(dataBlocks[i], 1);
					dataBlocks[i].free();
					if(persistent)
						dataBlocks[i].removeFrom(container);
					dataBlocks[i] = null;
				}
			}
		}
		
		if(persistent) {
			container.store(this);
			container.deactivate(parent, 1);
		}
		
		schedule(container, context);
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param ex
	 * @param container
	 * @param context
	 * @param parent
	 */
	void finish(InsertException ex, ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if (logMINOR)
			Logger.minor(this, "Finishing " + this + " with " + ex, ex);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = ex;
		}
		if(persistent) {
			container.store(this);
		}
		parent.segmentFinished(this, container, context);
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataBlocks[i] == null) continue;
			container.activate(dataBlocks[i], 1);
			dataBlocks[i].free();
			dataBlocks[i].removeFrom(container);
			dataBlocks[i] = null;
		}
		for(int i=0;i<checkBlocks.length;i++) {
			if(checkBlocks[i] == null) continue;
			container.activate(checkBlocks[i], 1);
			checkBlocks[i].free();
			checkBlocks[i].removeFrom(container);
			checkBlocks[i] = null;
		}
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param container
	 * @param context
	 * @param parent
	 */
	private void finish(ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if(logMINOR) Logger.minor(this, "Finishing "+this);
		if(persistent)
			container.activate(errors, 5);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if(blocksSucceeded < blocksCompleted)
				toThrow = InsertException.construct(errors);
		}
		if(persistent) {
			container.store(this);
			container.deactivate(errors, 5);
		}
		unregister(container, context);
		parent.segmentFinished(this, container, context);
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataBlocks[i] == null) continue;
			container.activate(dataBlocks[i], 1);
			dataBlocks[i].free();
			dataBlocks[i].removeFrom(container);
			dataBlocks[i] = null;
		}
		for(int i=0;i<checkBlocks.length;i++) {
			if(checkBlocks[i] == null) continue;
			container.activate(checkBlocks[i], 1);
			checkBlocks[i].free();
			checkBlocks[i].removeFrom(container);
			checkBlocks[i] = null;
		}
	}

	private void onEncode(int x, ClientCHK key, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Encoded block "+x+" on "+this);
		synchronized (this) {
			if (finished)
				return;
			if (x >= dataBlocks.length) {
				if (checkURIs[x - dataBlocks.length] != null) {
					return;
				}
				checkURIs[x - dataBlocks.length] = key;
			} else {
				if (dataURIs[x] != null) {
					return;
				}
				dataURIs[x] = key;
			}
			blocksGotURI++;
			if(persistent)
				container.store(this);
			if(logMINOR)
				Logger.minor(this, "Blocks got URI: "+blocksGotURI+" of "+(dataBlocks.length + checkBlocks.length));
			if (blocksGotURI != dataBlocks.length + checkBlocks.length)
				return;
			// Double check
			for (int i = 0; i < checkURIs.length; i++) {
				if (checkURIs[i] == null) {
					Logger.error(this, "Check URI " + i + " is null");
					return;
				}
			}
			for (int i = 0; i < dataURIs.length; i++) {
				if (dataURIs[i] == null) {
					Logger.error(this, "Data URI " + i + " is null");
					return;
				}
			}
			hasURIs = true;
		}
		if(persistent) {
			container.activate(parent, 1);
			container.store(this);
		}
		parent.segmentHasURIs(this, container, context);
		if(persistent)
			container.deactivate(parent, 1);
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public boolean isEncoded() {
		return encoded;
	}

	public int countCheckBlocks() {
		return checkBlocks.length;
	}

	public int countDataBlocks() {
		return dataBlocks.length;
	}

	public ClientCHK[] getCheckCHKs() {
		return checkURIs;
	}

	public ClientCHK[] getDataCHKs() {
		return dataURIs;
	}

	/** Get the InsertException for this segment.
	 * NOTE: This will be deleted when the segment is deleted! Do not store it or pass 
	 * it on!
	 */
	InsertException getException() {
		synchronized (this) {
			return toThrow;
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if (toThrow != null)
				toThrow = new InsertException(InsertException.CANCELLED);
		}
		cancelInner(container, context);
	}

	private void cancelInner(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Cancelling "+this);
		super.unregister(container, context);
		if(persistent) {
			container.store(this);
			container.activate(parent, 1);
		}
		parent.segmentFinished(this, container, context);
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataBlocks[i] == null) continue;
			if(persistent) container.activate(dataBlocks[i], 1);
			dataBlocks[i].free();
			if(persistent) dataBlocks[i].removeFrom(container);
			dataBlocks[i] = null;
		}
		for(int i=0;i<checkBlocks.length;i++) {
			if(checkBlocks[i] == null) continue;
			if(persistent) container.activate(checkBlocks[i], 1);
			checkBlocks[i].free();
			if(persistent) checkBlocks[i].removeFrom(container);
			checkBlocks[i] = null;
		}
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		Logger.error(this, "Illegal transition in SplitFileInserterSegment: "
				+ oldState + " -> " + newState);
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got onMetadata from " + state);
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
		Logger.error(this, "Should not happen: onBlockSetFinished(" + state
				+ ") on " + this);
	}

	public synchronized boolean hasURIs() {
		return hasURIs;
	}

	public synchronized boolean isFetchable() {
		return blocksCompleted >= dataBlocks.length;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	/**
	 * Force the remaining blocks which haven't been encoded so far to be
	 * encoded ASAP.
	 */
	public void forceEncode(ObjectContainer container, ClientContext context) {
		context.backgroundBlockEncoder.queue(this, container, context);
	}

	public void fail(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "Failing but already finished on "+this);
				return;
			}
			finished = true;
			Logger.error(this, "Insert segment failed: "+e+" for "+this, e);
			this.toThrow = e;
			if(persistent) container.store(this);
		}
		cancelInner(container, context);
	}
	
	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
			Logger.error(this, "Insert segment failed: "+t+" for "+this, t);
			this.toThrow = new InsertException(InsertException.INTERNAL_ERROR, "FEC failure: "+t, null);
		}
		cancelInner(container, context);
	}

	Bucket getBucket(int blockNum) {
		if(blockNum >= dataBlocks.length)
			return checkBlocks[blockNum - dataBlocks.length];
		else
			return dataBlocks[blockNum];
	}
	
	private BlockItem getBlockItem(ObjectContainer container, ClientContext context, int blockNum) throws IOException {
		Bucket sourceData = getBucket(blockNum);
		if(sourceData == null) {
			Logger.error(this, "Selected block "+blockNum+" but is null - already finished?? on "+this);
			return null;
		}
		boolean deactivateBucket = false;
		if(persistent) {
			deactivateBucket = !container.ext().isActive(sourceData);
			if(deactivateBucket)
				container.activate(sourceData, 1);
		}
		Bucket data = sourceData.createShadow();
		if(data == null) {
			data = context.tempBucketFactory.makeBucket(sourceData.size());
			BucketTools.copy(sourceData, data);
		}
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" : bucket "+sourceData+" shadow "+data);
		if(persistent) {
			if(deactivateBucket)
				container.deactivate(sourceData, 1);
		}
		return new BlockItem(this, blockNum, data, persistent);
	}
	
	private int hashCodeForBlock(int blockNum) {
		// FIXME: Standard hashCode() pattern assumes both inputs are evenly
		// distributed ... this is not true here.
		return hashCode() * (blockNum + 1);
	}
	
	private static class BlockItem implements SendableRequestItem {
		
		private final boolean persistent;
		private final Bucket copyBucket;
		private final int hashCode;
		/** STRICTLY for purposes of equals() !!! */
		private final SplitFileInserterSegment parent;
		private final int blockNum;
		
		BlockItem(SplitFileInserterSegment parent, int blockNum, Bucket bucket, boolean persistent) throws IOException {
			this.parent = parent;
			this.blockNum = blockNum;
			this.copyBucket = bucket;
			this.hashCode = parent.hashCodeForBlock(blockNum);
			this.persistent = persistent;
		}
		
		public void dump() {
			copyBucket.free();
		}
		
		public int hashCode() {
			return hashCode;
		}
		
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == parent && ((BlockItem)o).blockNum == blockNum) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == parent && ((FakeBlockItem)o).blockNum == blockNum) return true;
			}
			return false;
		}
		
	}
	
	// Used for testing whether a block is already queued.
	private class FakeBlockItem implements SendableRequestItem {
		
		private final int blockNum;
		private final int hashCode;
		
		FakeBlockItem(int blockNum) {
			this.blockNum = blockNum;
			this.hashCode = hashCodeForBlock(blockNum);
			
		}
		
		public void dump() {
			// Do nothing
		}
		
		public SplitFileInserterSegment getParent() {
			return SplitFileInserterSegment.this;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == SplitFileInserterSegment.this && ((BlockItem)o).blockNum == blockNum) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == SplitFileInserterSegment.this && ((FakeBlockItem)o).blockNum == blockNum) return true;
			}
			return false;
		}
	}
	
	@Override
	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		BlockItem block = (BlockItem) keyNum;
		synchronized(this) {
			if(finished) return;
		}
		// First report the error.
		if(persistent)
			container.activate(errors, 5);
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			Logger.error(this, "Collision on a CHK?!?!?");
			fail(new InsertException(InsertException.INTERNAL_ERROR, "Collision on a CHK", null), container, context);
			return;
		case LowLevelPutException.INTERNAL_ERROR:
			Logger.error(this, "Internal error: "+e, e);
			fail(new InsertException(InsertException.INTERNAL_ERROR, e.toString(), null), container, context);
			return;
		case LowLevelPutException.REJECTED_OVERLOAD:
			errors.inc(InsertException.REJECTED_OVERLOAD);
			break;
		case LowLevelPutException.ROUTE_NOT_FOUND:
			errors.inc(InsertException.ROUTE_NOT_FOUND);
			break;
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
			break;
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
			fail(new InsertException(InsertException.INTERNAL_ERROR, e.toString(), null), container, context);
			return;
		}
		if(persistent)
			container.store(errors);
		boolean isRNF = e.code == LowLevelPutException.ROUTE_NOT_FOUND ||
			e.code == LowLevelPutException.ROUTE_REALLY_NOT_FOUND;
		int blockNum = block.blockNum;
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" failed on "+this+" : "+e);
		boolean treatAsSuccess = false;
		boolean failedBlock = false;
		int completed;
		int succeeded;
		synchronized(this) {
			if(blockNum >= dataBlocks.length) {
				// Check block.
				int checkNum = blockNum - dataBlocks.length;
				if(checkFinished[checkNum]) {
					if(checkFailed[checkNum])
						Logger.error(this, "Got onFailure() but block has already failed! Check block "+checkNum+" on "+this);
					else
						Logger.error(this, "Got onFailure() but block has already succeeded: Check block "+checkNum+" on "+this);
					return;
				}
				if(isRNF) {
					checkConsecutiveRNFs[checkNum]++;
					if(persistent) container.activate(blockInsertContext, 1);
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+checkConsecutiveRNFs[checkNum]+" / "+blockInsertContext.consecutiveRNFsCountAsSuccess);
					if(checkConsecutiveRNFs[checkNum] == blockInsertContext.consecutiveRNFsCountAsSuccess) {
						// Treat as success
						treatAsSuccess = true;
					}
				} else {
					checkConsecutiveRNFs[checkNum] = 0;
				}
				if(!treatAsSuccess) {
					checkRetries[checkNum]++;
					if(checkRetries[checkNum] > maxRetries && maxRetries != -1) {
						failedBlock = true;
						// Treat as failed.
						checkFinished[checkNum] = true;
						checkFailed[checkNum] = true;
						blocksCompleted++;
						if(persistent) container.activate(blocks, 2);
						blocks.remove(new Integer(blockNum));
						if(persistent) container.store(blocks);
						if(checkBlocks[checkNum] != null) {
							if(persistent) container.activate(checkBlocks[checkNum], 1);
							checkBlocks[checkNum].free();
							if(persistent) checkBlocks[checkNum].removeFrom(container);
							checkBlocks[checkNum] = null;
							if(logMINOR) Logger.minor(this, "Failed to insert check block "+checkNum+" on "+this);
						} else {
							Logger.error(this, "Check block "+checkNum+" failed on "+this+" but bucket is already nulled out!");
						}
					}
					// Else we are still registered, but will have to be
					// re-selected: for persistent requests, the current
					// PersistentChosenRequest will not re-run the same block.
					// This is okay!
				} else {
					// Better handle it here to minimize race conditions. :|
					checkFinished[checkNum] = true;
					checkFailed[checkNum] = false; // Treating as succeeded
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(new Integer(blockNum));
					if(persistent) container.store(blocks);
					if(checkBlocks[checkNum] != null) {
						if(persistent) container.activate(checkBlocks[checkNum], 1);
						checkBlocks[checkNum].free();
						if(persistent) checkBlocks[checkNum].removeFrom(container);
						checkBlocks[checkNum] = null;
						if(logMINOR) Logger.minor(this, "Repeated RNF, treating as success for check block "+checkNum+" on "+this);
					} else {
						Logger.error(this, "Check block "+checkNum+" succeeded (sort of) on "+this+" but bucket is already nulled out!");
					}
				}
			} else {
				// Data block.
				if(dataFinished[blockNum]) {
					if(dataFailed[blockNum])
						Logger.error(this, "Got onFailure() but block has already failed! Data block "+blockNum+" on "+this);
					else
						Logger.error(this, "Got onFailure() but block has already succeeded: Data block "+blockNum+" on "+this);
					return;
				}
				if(isRNF) {
					dataConsecutiveRNFs[blockNum]++;
					if(persistent) container.activate(blockInsertContext, 1);
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+dataConsecutiveRNFs[blockNum]+" / "+blockInsertContext.consecutiveRNFsCountAsSuccess);
					if(dataConsecutiveRNFs[blockNum] == blockInsertContext.consecutiveRNFsCountAsSuccess) {
						// Treat as success
						treatAsSuccess = true;
					}
				} else {
					dataConsecutiveRNFs[blockNum] = 0;
				}
				if(!treatAsSuccess) {
					dataRetries[blockNum]++;
					if(dataRetries[blockNum] > maxRetries && maxRetries != -1) {
						failedBlock = true;
						// Treat as failed.
						dataFinished[blockNum] = true;
						dataFailed[blockNum] = true;
						blocksCompleted++;
						if(persistent) container.activate(blocks, 2);
						blocks.remove(new Integer(blockNum));
						if(persistent) container.store(blocks);
						if(dataBlocks[blockNum] != null) {
							if(persistent) container.activate(dataBlocks[blockNum], 1);
							dataBlocks[blockNum].free();
							if(persistent) dataBlocks[blockNum].removeFrom(container);
							dataBlocks[blockNum] = null;
							if(logMINOR) Logger.minor(this, "Failed to insert data block "+blockNum+" on "+this);
						} else {
							Logger.error(this, "Data block "+blockNum+" failed on "+this+" but bucket is already nulled out!");
						}
					}
					// Else we are still registered, but will have to be
					// re-selected: for persistent requests, the current
					// PersistentChosenRequest will not re-run the same block.
					// This is okay!
				} else {
					// Better handle it here to minimize race conditions. :|
					dataFinished[blockNum] = true;
					dataFailed[blockNum] = false; // Treating as succeeded
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(new Integer(blockNum));
					if(persistent) container.store(blocks);
					if(dataBlocks[blockNum] != null && encoded) {
						if(persistent) container.activate(dataBlocks[blockNum], 1);
						dataBlocks[blockNum].free();
						if(persistent) dataBlocks[blockNum].removeFrom(container);
						dataBlocks[blockNum] = null;
						if(logMINOR) Logger.minor(this, "Repeated RNF, treating as success for data block "+blockNum+" on "+this);
					} else {
						Logger.error(this, "Data block "+blockNum+" succeeded (sort of) on "+this+" but bucket is already nulled out!");
					}
				}
			}
			if(persistent)
				container.store(this);
			completed = blocksCompleted;
			succeeded = blocksSucceeded;
		}
		if(persistent) container.activate(putter, 1);
		if(failedBlock)
			putter.failedBlock(container, context);
		else if(treatAsSuccess)
			putter.completedBlock(false, container, context);
		if(persistent) container.deactivate(putter, 1);
		if(treatAsSuccess && succeeded == dataBlocks.length) {
			if(persistent) container.activate(parent, 1);
			parent.segmentFetchable(this, container);
			if(persistent) container.deactivate(parent, 1);
		} else if(completed == dataBlocks.length + checkBlocks.length) {
			if(persistent) container.activate(parent, 1);
			finish(container, context, parent);
			if(persistent) container.deactivate(parent, 1);
		}
	}

	@Override
	public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
		BlockItem block = (BlockItem) keyNum;
		int blockNum = block.blockNum;
		int completed;
		int succeeded;
		if(logMINOR) Logger.minor(this, "Block "+blockNum+" succeeded on "+this);
		synchronized(this) {
			if(finished) {
				return;
			}
			if(blockNum >= dataBlocks.length) {
				// Check block.
				int checkNum = blockNum - dataBlocks.length;
				if(!checkFinished[checkNum]) {
					checkFinished[checkNum] = true;
					checkFailed[checkNum] = false;
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(new Integer(blockNum));
					if(persistent) container.store(blocks);
				} else {
					if(checkFailed[checkNum])
						Logger.error(this, "Got onSuccess() but block has already failed! Check block "+checkNum+" on "+this);
					else
						Logger.error(this, "Got onSuccess() but block has already succeeded: Check block "+checkNum+" on "+this);
					return;
				}
				if(checkBlocks[checkNum] != null) {
					if(persistent) container.activate(checkBlocks[checkNum], 1);
					checkBlocks[checkNum].free();
					if(persistent) checkBlocks[checkNum].removeFrom(container);
					checkBlocks[checkNum] = null;
				} else {
					Logger.error(this, "Check block "+checkNum+" succeeded on "+this+" but bucket is already nulled out!");
				}
			} else {
				// Data block
				if(!dataFinished[blockNum]) {
					dataFinished[blockNum] = true;
					dataFailed[blockNum] = false;
					blocksCompleted++;
					blocksSucceeded++;
					if(persistent) container.activate(blocks, 2);
					blocks.remove(new Integer(blockNum));
					if(persistent) container.store(blocks);
				} else {
					if(dataFailed[blockNum])
						Logger.error(this, "Got onSuccess() but block has already failed! Data block "+blockNum+" on "+this);
					else
						Logger.error(this, "Got onSuccess() but block has already succeeded: Data block "+blockNum+" on "+this);
					return;
				}
				// Data blocks may not be freed until after we have encoded the check blocks.
				if(encoded && dataBlocks[blockNum] != null) {
					if(persistent) container.activate(dataBlocks[blockNum], 1);
					dataBlocks[blockNum].free();
					if(persistent) dataBlocks[blockNum].removeFrom(container);
					dataBlocks[blockNum] = null;
				} else if(dataBlocks[blockNum] == null) {
					Logger.error(this, "Data block "+blockNum+" succeeded on "+this+" but bucket is already nulled out!");
					if(persistent) Logger.minor(this, "Activation state: "+container.ext().isActive(this));
				}
			}
			if(persistent)
				container.store(this);
			completed = blocksCompleted;
			succeeded = blocksSucceeded;
		}
		if(persistent) container.activate(putter, 1);
		putter.completedBlock(false, container, context);
		if(persistent) container.deactivate(putter, 1);
		if(succeeded == dataBlocks.length) {
			if(persistent) container.activate(parent, 1);
			parent.segmentFetchable(this, container);
			if(persistent) container.deactivate(parent, 1);
		} else if(completed == dataBlocks.length + checkBlocks.length) {
			if(persistent) container.activate(parent, 1);
			finish(container, context, parent);
			if(persistent) container.deactivate(parent, 1);
		}
	}

	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		return countSendableKeys(container, context);
	}

	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(blocks, 1);
		}
		synchronized(this) {
			if(finished) return null;
			if(blocks.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "No blocks to remove");
				return null;
			}
			for(int i=0;i<10;i++) {
				Integer ret;
				int x;
				if(blocks.size() == 0) return null;
				x = context.random.nextInt(blocks.size());
				ret = blocks.get(x);
				int num = ret;
				
				// Check whether it is already running
				if(!persistent) {
					if(keys.hasTransientInsert(this, new FakeBlockItem(num)))
						return null;
				}

				try {
					return getBlockItem(container, context, num);
				} catch (IOException e) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
					return null;
				}
			}
			return null;
		}
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		if(persistent) container.activate(putter, 1);
		return putter.getClient();
	}

	@Override
	public ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(putter, 1);
		return putter.getPriorityClass();
	}

	@Override
	public int getRetryCount() {
		// No point scheduling inserts by retry count.
		// FIXME: Either implement sub-segments to schedule by retry count,
		// or (more likely imho) make the scheduler not care about retry counts for inserts.
		return 0;
	}

	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return new SendableRequestSender() {

			public boolean send(NodeClientCore core, RequestScheduler sched, final ClientContext context, ChosenBlock req) {
				// Ignore keyNum, key, since we're only sending one block.
				try {
					BlockItem block = (BlockItem) req.token;
					if(logMINOR) Logger.minor(this, "Starting request: "+SplitFileInserterSegment.this+" block number "+block.blockNum);
					ClientCHKBlock b;
					try {
						b = encodeBucket(block.copyBucket);
					} catch (CHKEncodeException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
					} catch (MalformedURLException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
					} catch (IOException e) {
						throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
					} finally {
						block.copyBucket.free();
					}
					if (b==null) {
						Logger.error(this, "Asked to send empty block on "+SplitFileInserterSegment.this, new Exception("error"));
						return false;
					}
					final ClientCHK key = (ClientCHK) b.getClientKey();
					final int num = block.blockNum;
					if(block.persistent) {
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							if(!container.ext().isStored(SplitFileInserterSegment.this)) return;
							container.activate(SplitFileInserterSegment.this, 1);
							onEncode(num, key, container, context);
							container.deactivate(SplitFileInserterSegment.this, 1);
						}

					}, NativeThread.NORM_PRIORITY+1, false);
					} else {
						context.mainExecutor.execute(new Runnable() {

							public void run() {
								onEncode(num, key, null, context);
							}
							
						}, "Got URI");
						
					}
					core.realPut(b, req.cacheLocalRequests);
				} catch (LowLevelPutException e) {
					req.onFailure(e, context);
					if(logMINOR) Logger.minor(this, "Request failed: "+SplitFileInserterSegment.this+" for "+e);
					return true;
				}
				if(logMINOR) Logger.minor(this, "Request succeeded: "+SplitFileInserterSegment.this);
				req.onInsertSuccess(context);
				return true;
			}
			
		};
	}

	protected ClientCHKBlock encodeBucket(Bucket copyBucket) throws CHKEncodeException, IOException {
		return ClientCHKBlock.encode(copyBucket, false, true, (short)-1, CHKBlock.DATA_LENGTH);
	}

	@Override
	public boolean isCancelled(ObjectContainer container) {
		return finished;
	}

	@Override
	public boolean isSSK() {
		return false;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blocks, 1);
		}
		Integer[] blockNumbers;
		synchronized(this) {
			blockNumbers = blocks.toArray(new Integer[blocks.size()]);
		}
		ArrayList<PersistentChosenBlock> ret = new ArrayList<PersistentChosenBlock>();
		Arrays.sort(blockNumbers);
		int prevBlockNumber = -1;
		for(int i=0;i<blockNumbers.length;i++) {
			int blockNumber = blockNumbers[i];
			if(blockNumber == prevBlockNumber) {
				Logger.error(this, "Duplicate block number in makeBlocks() in "+this+": two copies of "+blockNumber);
				continue;
			}
			prevBlockNumber = blockNumber;
			SendableRequestItem item;
			try {
				item = getBlockItem(container, context, blockNumber);
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				return null;
			}
			PersistentChosenBlock block = new PersistentChosenBlock(true, request, item, null, null, sched);
			if(logMINOR) Logger.minor(this, "Created block "+block+" for block number "+blockNumber+" on "+this);
			ret.add(block);
		}
		if(persistent) {
			container.deactivate(blocks, 1);
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret.size()+" blocks");
		return ret;
	}

	@Override
	public synchronized long countSendableKeys(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(blocks, 1);
		}
		int sz = blocks.size();
		if(persistent) {
			container.deactivate(blocks, 1);
		}
		return sz;
	}

	public synchronized boolean isEmpty(ObjectContainer container) {
		if(persistent) container.activate(blocks, 2);
		boolean ret = (finished || blocks.isEmpty());
		if(persistent) container.deactivate(blocks, 1);
		return ret;
	}

	private boolean removeOnEncode;
	
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(encodeJob != null) {
			if(!encodeJob.cancel(container, context)) {
				synchronized(this) {
					removeOnEncode = true;
					if(logMINOR) Logger.minor(this, "Will remove after encode finished: "+this);
					container.store(this);
					return;
				}
			}
			encodeJob = null;
		}
		// parent, putter can deal with themselves
		for(int i=0;i<dataBlocks.length;i++) {
			if(dataBlocks[i] == null) continue;
			container.activate(dataBlocks[i], 1);
			dataBlocks[i].free();
			dataBlocks[i].removeFrom(container);
			dataBlocks[i] = null;
		}
		for(int i=0;i<checkBlocks.length;i++) {
			if(checkBlocks[i] == null) continue;
			container.activate(checkBlocks[i], 1);
			checkBlocks[i].free();
			checkBlocks[i].removeFrom(container);
			checkBlocks[i] = null;
		}
		for(ClientCHK chk : dataURIs) {
			if(chk != null) {
				if(logMINOR) Logger.minor(this, "dataURI is null on "+this);
				container.activate(chk, 5);
				chk.removeFrom(container);
			}
		}
		for(ClientCHK chk : checkURIs) {
			if(chk != null) {
				if(logMINOR) Logger.minor(this, "checkURI is null on "+this);
				container.activate(chk, 5);
				chk.removeFrom(container);
			}
		}
		container.activate(blocks, 5);
		for(Integer i : blocks) {
			container.activate(i, 1);
			container.delete(i);
		}
		container.delete(blocks);
		if(toThrow != null) {
			container.activate(toThrow, 5);
			toThrow.removeFrom(container);
		}
		if(errors != null) {
			container.activate(errors, 1);
			errors.removeFrom(container);
		}
		container.delete(this);
	}
	
	@Override
	public boolean cacheInserts(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(blockInsertContext);
			if(deactivate)
				container.activate(blockInsertContext, 1);
		}
		boolean retval = blockInsertContext.cacheLocalRequests;
		if(deactivate)
			container.deactivate(blockInsertContext, 1);
		return retval;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(finished) {
			Logger.error(this, "Storing "+this+" when already finished!", new Exception("error"));
			return false;
		}
		if(logDEBUG) Logger.debug(this, "Storing "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
		return true;
	}

}
