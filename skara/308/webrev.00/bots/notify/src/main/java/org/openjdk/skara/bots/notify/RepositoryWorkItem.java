/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RepositoryWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<Tag> tagStorageBuilder;
    private final StorageBuilder<ResolvedBranch> branchStorageBuilder;
    private final List<RepositoryUpdateConsumer> updaters;

    RepositoryWorkItem(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<Tag> tagStorageBuilder, StorageBuilder<ResolvedBranch> branchStorageBuilder, List<RepositoryUpdateConsumer> updaters) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.updaters = updaters;
    }

    private void handleNewRef(Repository localRepo, Reference ref, Collection<Reference> allRefs) {
        // Figure out the best parent ref
        var candidates = new HashSet<>(allRefs);
        candidates.remove(ref);
        if (candidates.size() == 0) {
            log.warning("No parent candidates found for branch '" + ref.name() + "' - ignoring");
            return;
        }

        var bestParent = candidates.stream()
                                   .map(c -> {
                                       try {
                                           return new AbstractMap.SimpleEntry<>(c, localRepo.commits(c.hash().hex() + ".." + ref.hash(), true).asList());
                                       } catch (IOException e) {
                                           throw new UncheckedIOException(e);
                                       }
                                   })
                                   .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                                   .orElseThrow();
        if (bestParent.getValue().size() > 1000) {
            throw new RuntimeException("Excessive amount of unique commits on new branch " + ref.name() +
                                               " detected (" + bestParent.getValue().size() + ") - skipping notifications");
        }
        for (var updater : updaters) {
            var branch = new Branch(ref.name());
            var parent = new Branch(bestParent.getKey().name());
            updater.handleNewBranch(repository, bestParent.getValue(), parent, branch);
        }
    }

    private void handleUpdatedRef(Repository localRepo, Reference ref, List<Commit> commits) {
        for (var updater : updaters) {
            var branch = new Branch(ref.name());
            updater.handleCommits(repository, commits, branch);
        }
    }

    private void handleRef(Repository localRepo, UpdateHistory history, Reference ref, Collection<Reference> allRefs) throws IOException {
        var branch = new Branch(ref.name());
        var lastHash = history.branchHash(branch);
        if (lastHash.isEmpty()) {
            log.warning("No previous history found for branch '" + branch + "' - resetting mark");
            history.setBranchHash(branch, ref.hash());
            handleNewRef(localRepo, ref, allRefs);
        } else {
            var commits = localRepo.commits(lastHash.get() + ".." + ref.hash()).asList();
            if (commits.size() == 0) {
                return;
            }
            history.setBranchHash(branch, ref.hash());
            if (commits.size() > 1000) {
                throw new RuntimeException("Excessive amount of new commits on branch " + branch.name() +
                                                   " detected (" + commits.size() + ") - skipping notifications");
            }
            Collections.reverse(commits);
            handleUpdatedRef(localRepo, ref, commits);
        }
    }

    private Optional<OpenJDKTag> existingPrevious(OpenJDKTag tag, Set<OpenJDKTag> allJdkTags) {
        while (true) {
            var candidate = tag.previous();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            tag = candidate.get();
            if (!allJdkTags.contains(tag)) {
                continue;
            }
            return Optional.of(tag);
        }
    }

    private void handleTags(Repository localRepo, UpdateHistory history) throws IOException {
        var tags = localRepo.tags();
        var newTags = tags.stream()
                          .filter(tag -> !history.hasTag(tag))
                          .collect(Collectors.toList());

        if (tags.size() == newTags.size()) {
            if (tags.size() > 0) {
                log.warning("No previous tag history found - ignoring all current tags");
                history.addTags(tags);
            }
            return;
        }

        if (newTags.size() > 10) {
            history.addTags(newTags);
            throw new RuntimeException("Excessive amount of new tags detected (" + newTags.size() +
                                               ") - skipping notifications");
        }

        var allJdkTags = tags.stream()
                             .map(OpenJDKTag::create)
                             .filter(Optional::isPresent)
                             .map(Optional::get)
                             .collect(Collectors.toSet());
        var newJdkTags = newTags.stream()
                                .map(OpenJDKTag::create)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .sorted(Comparator.comparingInt(OpenJDKTag::buildNum))
                                .collect(Collectors.toList());
        for (var tag : newJdkTags) {
            // Update the history first - if there is a problem here we don't want to send out multiple updates
            history.addTags(List.of(tag.tag()));

            var commits = new ArrayList<Commit>();

            // Try to determine which commits are new since the last build
            var previous = existingPrevious(tag, allJdkTags);
            if (previous.isPresent()) {
                commits.addAll(localRepo.commits(previous.get().tag() + ".." + tag.tag()).asList());
            }

            // If none are found, just include the commit that was tagged
            if (commits.isEmpty()) {
                var commit = localRepo.lookup(tag.tag());
                if (commit.isEmpty()) {
                    throw new RuntimeException("Failed to lookup tag '" + tag.toString() + "'");
                } else {
                    commits.add(commit.get());
                }
            }

            Collections.reverse(commits);
            var annotation = localRepo.annotate(tag.tag());
            for (var updater : updaters) {
                updater.handleOpenJDKTagCommits(repository, commits, tag, annotation.orElse(null));
            }
        }

        var newNonJdkTags = newTags.stream()
                                   .filter(tag -> OpenJDKTag.create(tag).isEmpty())
                                   .collect(Collectors.toList());
        for (var tag : newNonJdkTags) {
            // Update the history first - if there is a problem here we don't want to send out multiple updates
            history.addTags(List.of(tag));

            var commit = localRepo.lookup(tag);
            if (commit.isEmpty()) {
                throw new RuntimeException("Failed to lookup tag '" + tag.toString() + "'");
            }

            var annotation = localRepo.annotate(tag);
            for (var updater : updaters) {
                updater.handleTagCommit(repository, commit.get(), tag, annotation.orElse(null));
            }
        }
    }

    private Repository fetchAll(Path dir, URI remote) throws IOException {
        Repository repo = null;
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            repo = Repository.clone(remote, dir);
        } else {
            repo = Repository.get(dir).orElseThrow(() -> new RuntimeException("Repository in " + dir + " has vanished"));
        }
        repo.fetchAll();
        return repo;
    }


    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof RepositoryWorkItem)) {
            return true;
        }
        RepositoryWorkItem otherItem = (RepositoryWorkItem) other;
        if (!repository.name().equals(otherItem.repository.name())) {
            return true;
        }
        return false;
    }

    @Override
    public void run(Path scratchPath) {
        var sanitizedUrl = URLEncoder.encode(repository.webUrl().toString() + "v2", StandardCharsets.UTF_8);
        var path = storagePath.resolve(sanitizedUrl);
        var historyPath = scratchPath.resolve("notify").resolve("history");

        try {
            var localRepo = fetchAll(path, repository.url());
            var history = UpdateHistory.create(tagStorageBuilder, historyPath.resolve("tags"), branchStorageBuilder, historyPath.resolve("branches"));
            handleTags(localRepo, history);

            var knownRefs = localRepo.remoteBranches("origin")
                                     .stream()
                                     .filter(ref -> branches.matcher(ref.name()).matches())
                                     .collect(Collectors.toList());
            boolean hasBranchHistory = knownRefs.stream()
                                                .map(ref -> history.branchHash(new Branch(ref.name())))
                                                .anyMatch(Optional::isPresent);
            for (var ref : knownRefs) {
                if (!hasBranchHistory) {
                    log.warning("No previous history found for any branch - resetting mark for '" + ref.name() + "'");
                    history.setBranchHash(new Branch(ref.name()), ref.hash());
                } else {
                    handleRef(localRepo, history, ref, knownRefs);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
