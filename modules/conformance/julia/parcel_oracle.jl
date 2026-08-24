#!/usr/bin/env julia
# Independent Julia oracle for the neutral parcel-domain binary profiles. It
# uses only the public ADR 0005 encoding plus SHA-256; no Neuropublish code.

using SHA
using JSON3
using Base64

const KEYS = [
    "schaefer2018-7networks-lh-visual-1",
    "schaefer2018-7networks-lh-default-1",
    "schaefer2018-7networks-rh-visual-1",
    "schaefer2018-7networks-rh-default-1",
]

digest(bytes) = "sha256:" * bytes2hex(sha256(bytes))

function finite_preimage(keys)
    length(keys) > 0 || error("keys must be non-empty")
    length(unique(keys)) == length(keys) || error("keys must be unique")
    io = IOBuffer()
    write(io, UInt8['N', 'P', 'U', 'D', 'O', 'M', '1', 0])
    for value in ("org.neuropublish.domain/finite-indexed", "1.0")
        bytes = Vector{UInt8}(codeunits(value))
        write(io, htol(UInt32(length(bytes))))
        write(io, bytes)
    end
    write(io, htol(UInt64(length(keys))))
    for key in keys
        bytes = Vector{UInt8}(codeunits(key))
        write(io, htol(UInt32(length(bytes))))
        write(io, bytes)
    end
    take!(io)
end

function hard_assignment(ordinals)
    io = IOBuffer()
    for ordinal in ordinals
        write(io, htol(Int32(ordinal)))
    end
    take!(io)
end

finite = finite_preimage(KEYS)
assignment = hard_assignment([0, 0, 1, 1, 2, 2, 3, 3])
reordered = finite_preimage(KEYS[[2, 1, 3, 4]])
foreign_keys = copy(KEYS)
foreign_keys[1] = "schaefer2018-17networks-lh-visual-1"
foreign = finite_preimage(foreign_keys)

println(JSON3.write((
    finiteFingerprint = digest(finite),
    finiteBytes = base64encode(finite),
    finiteSize = length(finite),
    assignmentDigest = digest(assignment),
    assignmentBytes = base64encode(assignment),
    assignmentSize = length(assignment),
    reorderedFingerprint = digest(reordered),
    foreignFingerprint = digest(foreign),
)))
