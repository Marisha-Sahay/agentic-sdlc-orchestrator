package io.github.marishasahay.agentic.agents.contract;

import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.util.List;

/**
 * Files an agent proposes (code, tests or docs). Full contents rather than patches: a patch that
 * does not apply cleanly is a worse failure mode than a slightly larger proposal.
 *
 * @param summary what changed and why
 * @param files proposed files
 * @param decisions implementation decisions worth recording
 */
public record CodeProposal(String summary, List<ProposedFile> files,
    List<DesignDecision> decisions) {

  /**
   * A proposed file.
   *
   * @param path workspace-relative path
   * @param content complete new content
   */
  public record ProposedFile(String path, String content) {}

  public List<FileChange> toChanges() {
    return files == null ? List.of()
        : files.stream().map(f -> FileChange.write(f.path(), f.content())).toList();
  }
}
