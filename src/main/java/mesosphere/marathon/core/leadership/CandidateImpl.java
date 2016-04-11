package mesosphere.marathon.core.leadership;

// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.twitter.common.base.Command;
import com.twitter.common.base.ExceptionalCommand;
import com.twitter.common.zookeeper.Candidate;
import com.twitter.common.zookeeper.Group;
import com.twitter.common.zookeeper.ZooKeeperClient;
import org.apache.zookeeper.KeeperException;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;

/**
 * Implements leader election for small groups of candidates.  This implementation is subject to the
 * <a href="http://hadoop.apache.org/zookeeper/docs/r3.2.1/recipes.html#sc_leaderElection">
 * herd effect</a> for a given group and should only be used for small (~10 member) candidate pools.
 */
public class CandidateImpl implements Candidate {
    private static final Logger LOG = Logger.getLogger(CandidateImpl.class.getName());

    private static final byte[] UNKNOWN_CANDIDATE_DATA = "<unknown>".getBytes(Charsets.UTF_8);

    private static final Supplier<byte[]> IP_ADDRESS_DATA_SUPPLIER = new Supplier<byte[]>() {
        @Override public byte[] get() {
            try {
                return InetAddress.getLocalHost().getHostAddress().getBytes();
            } catch (UnknownHostException e) {
                LOG.log(Level.WARNING, "Failed to determine local address!", e);
                return UNKNOWN_CANDIDATE_DATA;
            }
        }
    };

    private static final Function<Iterable<String>, String> MOST_RECENT_JUDGE =
            new Function<Iterable<String>, String>() {
                @Override public String apply(Iterable<String> candidates) {
                    return Ordering.natural().min(candidates);
                }
            };

    private final Group group;
    private final Function<Iterable<String>, String> judge;
    private final Supplier<byte[]> dataSupplier;
    private final AtomicReference<Group.GroupChangeListener> groupChangeListener;
    private Command cancelWatch;

    /**
     * Equivalent to {@link #CandidateImpl(Group, com.google.common.base.Function, Supplier)} using a
     * judge that always picks the lowest numbered candidate ephemeral node - by proxy the oldest or
     * 1st candidate and a default supplier that provides the ip address of this host according to
     * {@link java.net.InetAddress#getLocalHost()} as the leader identifying data.
     */
    public CandidateImpl(Group group) {
        this(group, MOST_RECENT_JUDGE, IP_ADDRESS_DATA_SUPPLIER);
    }

    /**
     * Creates a candidate that can be used to offer leadership for the given {@code group} using
     * a judge that always picks the lowest numbered candidate ephemeral node - by proxy the oldest
     * or 1st. The dataSupplier should produce bytes that identify this process as leader. These bytes
     * will become available to all participants via the {@link Candidate#getLeaderData()} method.
     */
    public CandidateImpl(Group group, Supplier<byte[]> dataSupplier) {
        this(group, MOST_RECENT_JUDGE, dataSupplier);
    }

    /**
     * Creates a candidate that can be used to offer leadership for the given {@code group}.  The
     * {@code judge} is used to pick the current leader from all group members whenever the group
     * membership changes. To form a well-behaved election group with one leader, all candidates
     * should use the same judge. The dataSupplier should produce bytes that identify this process
     * as leader. These bytes will become available to all participants via the
     * {@link Candidate#getLeaderData()} method.
     */
    public CandidateImpl(
            Group group,
            Function<Iterable<String>, String> judge,
            Supplier<byte[]> dataSupplier) {
        this.group = Preconditions.checkNotNull(group);
        this.judge = Preconditions.checkNotNull(judge);
        this.dataSupplier = Preconditions.checkNotNull(dataSupplier);
        this.groupChangeListener = new AtomicReference<Group.GroupChangeListener>(null);
        this.cancelWatch = null;
    }

    @Override
    public Optional<byte[]> getLeaderData()
            throws ZooKeeperClient.ZooKeeperConnectionException, KeeperException, InterruptedException {

        String leaderId = getLeader(group.getMemberIds());
        return leaderId == null
                ? Optional.<byte[]>absent()
                : Optional.of(group.getMemberData(leaderId));
    }

    @Override
    public Supplier<Boolean> offerLeadership(final Candidate.Leader leader)
            throws Group.JoinException, Group.WatchException, InterruptedException {

        // start a group watch, but only once in the life time of the the CandidateImpl.
        if (this.cancelWatch == null) {
            this.cancelWatch = this.group.watch(new Group.GroupChangeListener() {
                @Override
                public void onGroupChange(Iterable<String> memberIds) {
                    Group.GroupChangeListener listener = groupChangeListener.get();
                    if (listener != null) {
                        listener.onGroupChange(memberIds);
                    }
                }
            });
        }

        // listen for group member changes
        final AtomicBoolean elected = new AtomicBoolean(false);
        final AtomicBoolean abdicated = new AtomicBoolean(false);
        final AtomicReference<Group.Membership> membershipRef = new AtomicReference<Group.Membership>(null);
        final AtomicReference<Iterable<String>> pendingChange = new AtomicReference<Iterable<String>>(null);
        this.groupChangeListener.set(new Group.GroupChangeListener() {
            @Override
            public void onGroupChange(Iterable<String> memberIds) {
                boolean noCandidates = Iterables.isEmpty(memberIds);
                Group.Membership membership;
                synchronized (CandidateImpl.this) {
                    membership = membershipRef.get();
                    if (membership == null) {
                        pendingChange.set(memberIds);
                        return;
                    } else {
                        pendingChange.set(null);
                    }
                }
                String memberId = membership.getMemberId();

                if (noCandidates) {
                    LOG.warning("All candidates have temporarily left the group: " + group);
                } else if (!Iterables.contains(memberIds, memberId)) {
                    LOG.severe(String.format(
                            "Current member ID %s is not a candidate for leader, current voting: %s",
                            memberId, memberIds));
                } else {
                    boolean electedLeader = memberId.equals(getLeader(memberIds));
                    boolean previouslyElected = elected.getAndSet(electedLeader);

                    if (!previouslyElected && electedLeader) {
                        LOG.info(String.format("Candidate %s is now leader of group: %s",
                                membership.getMemberPath(), memberIds));

                        leader.onElected(new ExceptionalCommand<Group.JoinException>() {
                            @Override
                            public void execute() throws Group.JoinException {
                                Group.Membership membership = membershipRef.get();
                                if (membership != null) {
                                    membership.cancel();
                                }
                                abdicated.set(true);
                            }
                        });
                    } else if (!electedLeader) {
                        if (previouslyElected) {
                            leader.onDefeated();
                        }
                        LOG.info(String.format(
                                "Candidate %s waiting for the next leader election, current voting: %s",
                                membership.getMemberPath(), memberIds));
                    }
                }
            }
        });

        // join the group
        membershipRef.set(group.join(dataSupplier, new Command() {
            @Override public void execute() {
                membershipRef.set(null);
                leader.onDefeated();
            }
        }));

        // possibly the upper membershipRef.set is not finished yet when the groupChangeListener
        // fires above. Then one onGroupChange call is pending which is executed here:
        synchronized (this) {
            Iterable<String> memberIds = pendingChange.getAndSet(null);
            if (memberIds != null) {
                this.groupChangeListener.get().onGroupChange(memberIds);
            }
        }

        return new Supplier<Boolean>() {
            @Override public Boolean get() {
                return !abdicated.get() && elected.get();
            }
        };
    }

    @Nullable
    private String getLeader(Iterable<String> memberIds) {
        return Iterables.isEmpty(memberIds) ? null : judge.apply(memberIds);
    }
}
