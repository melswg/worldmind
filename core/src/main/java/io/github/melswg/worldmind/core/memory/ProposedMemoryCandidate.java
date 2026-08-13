package io.github.melswg.worldmind.core.memory;

/** Candidate payload accepted only as a proposed record with trusted provenance validation. */
public sealed interface ProposedMemoryCandidate permits ProposedFactCandidate, ProposedRelationshipCandidate {
    MemoryScope scope();
    MemoryVisibility visibility();
    JournalSequenceRange sourceRange();
    MemoryConfidence confidence();
    MemoryImportance importance();
}
