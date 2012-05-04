package com.clearspring.analytics.stream.quantile;

import java.util.*;

/**
 * Q-Digest datastructure.
 * <p>
 * Answers approximate quantile queries: actual rank of the result of query(q)
 * is in q-eps .. q+eps, where eps = log(sigma)/compressionFactor
 * and log(sigma) is ceiling of binary log of the largest value inserted,
 * i.e. height of the tree.
 * <p>
 * Two Q-Digests can be joined (see {@link #unionOf(QDigest, QDigest)}).
 * <p>
 * Source:
 * N.Shrivastava, C.Buragohain, D.Agrawal
 * Medians and Beyond: New Aggregation Techniques for Sensor Networks
 * http://www.cs.virginia.edu/~son/cs851/papers/ucsb.sensys04.pdf
 * <p>
 * This is a slightly modified version.
 * There is a small problem with the compression algorithm in the paper,
 * see https://plus.google.com/u/0/109909935680879695595/posts/768ZZ9Euqz6
 * <p>
 * So we use a different algorithm here:
 * <ul>
 *     <li>When an item is inserted, we compress along the path to root from the item's leaf
 *     <li>When the structure becomes too large (above the theoretical bound), or
 *     at "too destructive" operations (e.g. union or rebuild) we compress fully
 * </ul>
 *
 * Note that the accuracy of the structure does NOT suffer if "property 2"
 * from the paper is violated (in fact, restoring property 2 at any node
 * decreases accuracy).
 *
 * So we can say that we preserve the paper's accuracy and memory consumption claims.
 */
public class QDigest implements IQuantileEstimator {
    private long size;
    private int logCapacity;
    private double compressionFactor;
    private Map<Long,Long> node2count = new HashMap<Long,Long>();

    public QDigest(double compressionFactor) {
	this.compressionFactor = compressionFactor;
    }

    private long    value2leaf(long  x) { return (1L << logCapacity) + x; }
    private long    leaf2value(long id) { return id - (1L << logCapacity); }
    private boolean isRoot    (long id) { return id==1; }
    private boolean isLeaf    (long id) { return id >= 1L << logCapacity; }
    private long    sibling   (long id) { return (id%2 == 0) ? (id+1) : (id-1); }
    private long    parent    (long id) { return id/2; }
    private long    leftChild (long id) { return 2*id; }
    private long    rightChild(long id) { return 2*id + 1; }
    private long    rangeLeft (long id) { while (!isLeaf(id)) id = leftChild(id); return leaf2value(id); }
    private long    rangeRight(long id) { while (!isLeaf(id)) id = rightChild(id); return leaf2value(id); }

    @Override
    public void offer(long value) {
	// Rebuild if the value is too large for the current tree height
	if(value >= 1L << logCapacity) {
	    int newLogCapacity = logCapacity;
	    while(value >= 1L << newLogCapacity)
		newLogCapacity++;

	    rebuildToLogCapacity(newLogCapacity);
	}

	long leaf = value2leaf(value);
	node2count.put(leaf, get(leaf)+1);
	size++;
	// Always compress at the inserted node, and recompress fully
	// if the tree becomes too large.
	// This is one sensible strategy which both is fast and keeps
	// the tree reasonably small (within the theoretical bound of 3k nodes)
	compressUpward(leaf);
	if(node2count.size() > 3 * compressionFactor) {
	    compressFully();
	}
    }

    public static QDigest unionOf(QDigest a, QDigest b) {
	if(a.compressionFactor != b.compressionFactor) {
	    throw new IllegalArgumentException(
		    "Compression factors must be the same: " +
		    "left is " + a.compressionFactor + ", " +
		    "right is " + b.compressionFactor);
	}
	if(a.logCapacity > b.logCapacity)
	    return unionOf(b,a);

	QDigest res = new QDigest(a.compressionFactor);
	res.logCapacity = a.logCapacity;
	for(long k : a.node2count.keySet())
	    res.node2count.put(k, a.node2count.get(k));

	if(b.logCapacity > res.logCapacity)
	    res.rebuildToLogCapacity(b.logCapacity);

	for(long k : b.node2count.keySet())
	    res.node2count.put(k, b.get(k) + res.get(k));

	res.compressFully();

	return res;
    }

    private void rebuildToLogCapacity(int newLogCapacity) {
	Map<Long,Long> newNode2count = new HashMap<Long,Long>();
	// rebuild to newLogCapacity.
	// This means that our current tree becomes a leftmost subtree
	// of the new tree.
	// E.g. when rebuilding a tree with logCapacity = 2
	// (i.e. storing values in 0..3) to logCapacity = 5 (i.e. 0..31):
	// node 1 => 8 (+= 7 = 2^0*(2^3-1))
	// nodes 2..3 => 16..17 (+= 14 = 2^1*(2^3-1))
	// nodes 4..7 => 32..35 (+= 28 = 2^2*(2^3-1))
	// This is easy to see if you draw it on paper.
	// Process the keys by "layers" in the original tree.
	long scaleR = (1L << (newLogCapacity - logCapacity)) - 1;
	Long[] keys = node2count.keySet().toArray(new Long[node2count.size()]);
	Arrays.sort(keys);
	long scaleL = 1;
	for(long k : keys) {
	    while(scaleL <= k/2) scaleL <<= 1;
	    newNode2count.put(k + scaleL*scaleR, node2count.get(k));
	}
	node2count = newNode2count;
	logCapacity = newLogCapacity;
	compressFully();
    }

    private void compressFully() {
	// Restore property 2 at each node.
	Long[] allNodes = node2count.keySet().toArray(new Long[0]);
	for(long node : allNodes) {
	    compressDownward(node);
	}
    }

    /**
     * Restore P2 at node and upward the spine. Note that P2 can vanish
     * at some nodes sideways as a result of this. We'll fix that later
     * in compressFully when needed.
     */
    private void compressUpward(long node) {
	double threshold = Math.floor(size / compressionFactor);
	long atNode = get(node);
	while(!isRoot(node)) {
	    if(atNode > threshold) break;
	    long atSibling = get(sibling(node));
	    if(atNode + atSibling > threshold) break;
	    long atParent = get(parent(node));
	    if (atNode + atSibling + atParent > threshold) break;

	    node2count.put(parent(node), atParent + atNode + atSibling);
	    node2count.remove(node);
	    if(atSibling > 0)
		node2count.remove(sibling(node));
	    node = parent(node);
	    atNode = atParent + atNode + atSibling;
	}
    }

    /**
     * Restore P2 at seedNode and guarantee that no new violations of P2 appeared.
     */
    private void compressDownward(long seedNode) {
	double threshold = Math.floor(size / compressionFactor);
	// P2 check same as above but shorter and slower (and invoked rarely)
	for(Queue<Long> q = new LinkedList<Long>(Arrays.asList(seedNode)); !q.isEmpty();) {
	    long node = q.poll();
	    long atNode = get(node);
	    long atSibling = get(sibling(node));
	    if(atNode == 0 && atSibling == 0) continue;
	    long atParent = get(parent(node));
	    if(atParent + atNode + atSibling > threshold) continue;
	    node2count.put(parent(node), atParent + atNode + atSibling);
	    node2count.remove(node);
	    node2count.remove(sibling(node));
	    // Now P2 could have vanished at the node's and sibling's subtrees since they decreased.
	    if(!isLeaf(node)) {
		q.offer(leftChild(node));
		q.offer(leftChild(sibling(node)));
	    }
	}
    }

    private long get(long node) {
	Long res = node2count.get(node);
	return (res == null) ? 0 : res;
    }

    @Override
    public long getQuantile(double q) {
	List<long[]> ranges = toAscRanges();
	long s = 0;
	for(long[] r : ranges) {
	    if(s > q * size)
		return r[1];
	    s += r[2];
	}
	return ranges.get(ranges.size()-1)[1];
    }

    public List<long[]> toAscRanges() {
	List<long[]> ranges = new ArrayList<long[]>();
	for(long key : node2count.keySet())
	    ranges.add(new long[]{rangeLeft(key), rangeRight(key), node2count.get(key)});

	Collections.sort(ranges, new Comparator<long[]>() {
	    @Override
	    public int compare(long[] ra, long[] rb) {
		long rightA = ra[1], rightB = rb[1], sizeA = ra[1] - ra[0], sizeB = rb[1] - rb[0];
		if (rightA < rightB) return -1;
		if (rightA > rightB) return 1;
		if (sizeA < sizeB) return -1;
		if (sizeA > sizeB) return 1;
		return 0;
	    }
	});
	return ranges;
    }

}
