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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class LabelerWorkItem extends PullRequestWorkItem {
    LabelerWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    @Override
    public String toString() {
        return "LabelerWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    private Set<String> getLabels(Repository localRepo) throws IOException {
        var files = PullRequestUtils.changedFiles(pr, localRepo);
        return bot.labelConfiguration().fromChanges(files);
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        if (bot.currentLabels().containsKey(pr.headHash())) {
            return List.of();
        }
        try {
            var path = scratchPath.resolve("pr").resolve("labeler").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);
            var newLabels = getLabels(localRepo);
            var currentLabels = pr.labels().stream()
                                  .filter(key -> bot.labelConfiguration().allowed().contains(key))
                                  .collect(Collectors.toSet());

            // Add all labels not already set
            newLabels.stream()
                     .filter(label -> !currentLabels.contains(label))
                     .forEach(pr::addLabel);

            // Remove set labels no longer present
            currentLabels.stream()
                         .filter(label -> !newLabels.contains(label))
                         .forEach(pr::removeLabel);

            bot.currentLabels().put(pr.headHash(), Boolean.TRUE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }
}
