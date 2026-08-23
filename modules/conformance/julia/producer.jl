#!/usr/bin/env julia
# Neuropublish neutrality proof (ADR 0001): a foreign producer that builds and
# publishes a bundle from documented ingredients only — the manifest vocabulary
# (modules/conformance/fixtures/reference/manifest.json and the JSON Schema),
# the byte profile, the digest rule (SHA-256 over the manifest bytes as
# written), the volume-grid identity preimage (ADR 0005), the NIfTI-1 format,
# and the HTTP upload/commit protocol. No Neuropublish code, no generated client.
#
# Usage:
#   julia producer.jl --out DIR                      # write the bundle only
#   julia producer.jl --out DIR --server URL --project WS/PROJ --token T \
#                     [--parent REVISION] [--message TEXT]
#
# Prints `digest sha256:<hex>` for the bundle; with --server also prints
# `revision <id>`, `viewUrl <url>`, `server-digest sha256:<hex>` and one
# `rendition <asset> <status>` line per volume. Exits non-zero with a
# one-line `error: ...` message on any failure.

using SHA
using JSON3
using Downloads

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

function parse_args(argv)
    opts = Dict{String,String}()
    i = 1
    while i <= length(argv)
        a = argv[i]
        startswith(a, "--") || fail("unexpected argument $a")
        i == length(argv) && fail("missing value for $a")
        opts[a[3:end]] = argv[i + 1]
        i += 2
    end
    haskey(opts, "out") || fail("--out DIR is required")
    if haskey(opts, "server")
        for k in ("project", "token")
            haskey(opts, k) || fail("--$k is required with --server")
        end
        occursin('/', opts["project"]) || fail("--project must be workspace/project")
    end
    opts
end

function fail(msg)
    println(stderr, "error: ", msg)
    exit(1)
end

# ---------------------------------------------------------------------------
# NIfTI-1: a 352-byte little-endian header (348 + 4 extension bytes), float32
# voxels (datatype 16, bitpix 32), vox_offset 352, qform and sform both set
# to the same RAS+ affine (sform_code = qform_code = 1, NIFTI_XFORM_SCANNER_ANAT).
# ---------------------------------------------------------------------------

const SHAPE = (16, 16, 12)
const SPACING = (2.0, 2.0, 2.0)
const ORIGIN = (-15.0, -15.0, -11.0)

function affine()
    [SPACING[1] 0.0 0.0 ORIGIN[1];
     0.0 SPACING[2] 0.0 ORIGIN[2];
     0.0 0.0 SPACING[3] ORIGIN[3];
     0.0 0.0 0.0 1.0]
end

# Write `v` little-endian into `buf` at 0-based byte offset `off`; returns the next offset.
function put!(buf::Vector{UInt8}, off::Int, v)
    b = reinterpret(UInt8, [htol(v)])
    buf[off+1:off+length(b)] = b
    off + length(b)
end

function nifti_bytes(data::Array{Float32,3})
    hdr = zeros(UInt8, 348)
    put!(hdr, 0, Int32(348))                               # sizeof_hdr
    p = put!(hdr, 40, Int16(3))                            # dim[0]
    for n in SHAPE
        p = put!(hdr, p, Int16(n))                         # dim[1..3]
    end
    for _ in 4:7
        p = put!(hdr, p, Int16(1))                         # dim[4..7]
    end
    put!(hdr, 70, Int16(16))                               # datatype NIFTI_TYPE_FLOAT32
    put!(hdr, 72, Int16(32))                               # bitpix
    p = put!(hdr, 76, Float32(1.0))                        # pixdim[0] (qfac)
    for s in SPACING
        p = put!(hdr, p, Float32(s))                       # pixdim[1..3]
    end
    put!(hdr, 108, Float32(352))                           # vox_offset
    put!(hdr, 112, Float32(1.0))                           # scl_slope
    put!(hdr, 116, Float32(0.0))                           # scl_inter
    put!(hdr, 123, UInt8(2 | 8))                           # xyzt_units: mm | sec
    put!(hdr, 252, Int16(1))                               # qform_code
    put!(hdr, 254, Int16(1))                               # sform_code
    p = 256
    for q in (0.0f0, 0.0f0, 0.0f0)
        p = put!(hdr, p, q)                                # quatern b c d (identity rotation)
    end
    for o in ORIGIN
        p = put!(hdr, p, Float32(o))                       # qoffset x y z
    end
    A = affine()
    p = 280
    for r in 1:3, c in 1:4
        p = put!(hdr, p, Float32(A[r, c]))                 # srow_x, srow_y, srow_z
    end
    hdr[345:348] = UInt8['n', '+', '1', 0]                 # magic
    io = IOBuffer()
    write(io, hdr)
    write(io, zeros(UInt8, 4))                             # extension flag: none
    # x-fastest voxel order: Julia column-major matches NIfTI's i-fastest layout
    for k in 1:SHAPE[3], j in 1:SHAPE[2], i in 1:SHAPE[1]
        write(io, htol(data[i, j, k]))
    end
    take!(io)
end

function synthetic(kind::Symbol)
    data = zeros(Float32, SHAPE)
    cx, cy, cz = (SHAPE .+ 1) ./ 2
    for k in 1:SHAPE[3], j in 1:SHAPE[2], i in 1:SHAPE[1]
        r2 = ((i - cx)^2 + (j - cy)^2 + (k - cz)^2) / 18
        g = exp(-r2)
        data[i, j, k] = if kind == :t1
            Float32(100 * (1 - 0.5 * g) * (r2 < 3 ? 1 : 0.4))
        elseif kind == :effect
            Float32(0.8 * g - 0.2 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        elseif kind == :t
            Float32(6.0 * g - 3.5 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        else
            Float32(5.5 * g - 3.2 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        end
    end
    data
end

# ---------------------------------------------------------------------------
# Identity helpers
# ---------------------------------------------------------------------------

digest(bytes) = "sha256:" * bytes2hex(sha256(bytes))

# ADR 0005 volume-grid/v1 structural fingerprint preimage: magic, six
# length-prefixed UTF-8 strings, three Int32 shape entries, sixteen Float64
# affine entries (row-major), all little-endian.
function volume_grid_fingerprint(descriptor_id, descriptor_version, space, convention, unit, layout)
    io = IOBuffer()
    write(io, UInt8['N', 'P', 'U', 'D', 'O', 'M', '1', 0])
    for s in (descriptor_id, descriptor_version, space, convention, unit, layout)
        b = Vector{UInt8}(codeunits(s))
        write(io, htol(Int32(length(b))))
        write(io, b)
    end
    for n in SHAPE
        write(io, htol(Int32(n)))
    end
    A = affine()
    for r in 1:4, c in 1:4
        v = A[r, c]
        write(io, htol(v == 0.0 ? 0.0 : v))                # no negative zero
    end
    digest(take!(io))
end

# ---------------------------------------------------------------------------
# Manifest (core 0.1 vocabulary as used by the reference bundle)
# ---------------------------------------------------------------------------

const VOLUME_GRID_SCHEMA = (
    id = "org.neuropublish.domain/volume-grid",
    version = "1.0",
    digest = "sha256:c1871091d7dc2bf6c5d3b1acafdf2d9c0d47e62d5a737a571ed7433ba778b7ac",
)
const RECEIPT_SCHEMA = (
    id = "org.bbuchsbaum.fmrireg/analysis-receipt",
    version = "1.0",
    digest = "sha256:aa00000000000000000000000000000000000000000000000000000000000001",
)

function manifest(assets)
    affine_rows = [[affine()[r, c] for c in 1:4] for r in 1:4]
    fingerprint = volume_grid_fingerprint(
        VOLUME_GRID_SCHEMA.id, VOLUME_GRID_SCHEMA.version,
        "MNI152NLin2009cAsym", "RAS+", "mm", "x-fastest")
    asset(id; extra...) = (
        id = id,
        digest = assets[id].digest,
        mediaType = "application/x-nifti",
        size = assets[id].size,
        extra...,
    )
    field(id, measure, order; extra...) = (
        id = id,
        estimand = "speech",
        measure = measure,
        selection = (level = "group",),
        domain = "grid-2mm",
        representations = [(kind = "volume", asset = id)],
        order = order,
        extra...,
    )
    (
        core = "0.1",
        title = "Julia producer — synthetic speech group model",
        synopsis = "Written by modules/conformance/julia/producer.jl from documented ingredients only.",
        sensitivity = "group-level",
        axes = [(id = "level", label = "Level", values = ["group"])],
        domains = [(
            id = "grid-2mm",
            key = (
                descriptor = VOLUME_GRID_SCHEMA,
                size = prod(SHAPE),
                structuralFingerprint = fingerprint,
            ),
            descriptor = (
                schema = VOLUME_GRID_SCHEMA,
                payload = (
                    space = "MNI152NLin2009cAsym",
                    coordinateConvention = "RAS+",
                    spatialUnit = "mm",
                    ordinalLayout = "x-fastest",
                    shape = collect(SHAPE),
                    affine = affine_rows,
                ),
            ),
        )],
        assets = [
            # `x-julia-voxelStats` is an unknown field inside a known record: it
            # must survive admission, storage, and Scala/R round trips.
            asset("t1"; catalog = "synthetic:julia/t1", var"x-julia-voxelStats" = (min = 0.0, max = 100.0)),
            asset("speech-effect"),
            asset("speech-t"),
            asset("speech-z"),
        ],
        analyses = [(
            id = "group-model",
            label = "Group model · speech (synthetic)",
            method = (
                schema = (
                    id = "org.example.julia/reducer/mean",
                    version = "0.1",
                    digest = "sha256:cc00000000000000000000000000000000000000000000000000000000000003",
                ),
                payload = (weights = "equal", inputs = 12),
            ),
            sampleSize = 12,
            estimands = [(id = "speech", label = "speech coefficient", order = 1)],
        )],
        resultFields = [
            field("speech-effect", "org.neuropublish.measure/effect", 1),
            field("speech-t", "org.neuropublish.measure/t-statistic", 2;
                publishedDisplay = (
                    threshold = (mode = "two-sided", min = 2.5),
                    window = (min = -6.0, centre = 0.0, max = 6.0),
                    colormap = "cold-hot",
                )),
            field("speech-z", "org.neuropublish.measure/z-statistic", 3;
                publishedDisplay = (
                    threshold = (mode = "two-sided", min = 2.3),
                    window = (min = -5.0, centre = 0.0, max = 5.0),
                    colormap = "cold-hot",
                )),
        ],
        underlays = [(asset = "t1", domain = "grid-2mm", label = "Synthetic T1")],
        provenance = (
            entities = [(id = "raw", label = "Synthetic raw data", hosted = false)],
            activities = [
                (id = "first-level-01", schema = RECEIPT_SCHEMA,
                    payload = (subject = "01", temporalNoise = "AR(2)", drift = "cosine-128s", hrf = "spmg1")),
                (id = "first-level-02", schema = RECEIPT_SCHEMA,
                    payload = (subject = "02", temporalNoise = "AR(1)", drift = "cosine-128s", hrf = "spmg1")),
                (id = "denoise", schema = (
                        id = "org.example.julia/denoise",
                        version = "0.1",
                        digest = "sha256:dd00000000000000000000000000000000000000000000000000000000000004",
                    ),
                    payload = (method = "wavelet", levels = 3, threshold = 0.05)),
            ],
            edges = [
                (from = "raw", to = "first-level-01"),
                (from = "raw", to = "first-level-02"),
                (from = "first-level-01", to = "denoise"),
                (from = "first-level-02", to = "denoise"),
                (from = "denoise", to = "speech-t"),
            ],
        ),
        # Unknown top-level field: the server and every round trip must preserve it.
        var"x-julia-producer" = (
            version = "0.1",
            julia = "$(VERSION.major).$(VERSION.minor)",
            note = "unknown top-level extension; value preservation, not byte preservation",
            nested = (flags = [true, false], empty = Any[], ratio = 0.125),
        ),
    )
end

# ---------------------------------------------------------------------------
# HTTP (stdlib Downloads over libcurl; no HTTP.jl dependency)
# ---------------------------------------------------------------------------

function request(method, url, headers, body::Union{Nothing,Vector{UInt8}})
    out = IOBuffer()
    hs = [k => v for (k, v) in headers]
    r = Downloads.request(url;
        method = method,
        headers = hs,
        input = body === nothing ? nothing : IOBuffer(body),
        output = out,
        throw = false)
    r isa Downloads.Response || fail("$method $url: $(r.message)")
    r.status, String(take!(out))
end

function expect(status_ok, method, url, headers, body)
    status, text = request(method, url, headers, body)
    status == status_ok || fail("$method $url returned HTTP $status: $(strip(text))")
    text
end

function publish(opts, bundle_dir, manifest_bytes, manifest_digest, assets)
    server = rstrip(opts["server"], '/')
    ws, proj = split(opts["project"], '/'; limit = 2)
    bearer = "Authorization" => "Bearer " * opts["token"]
    json = "Content-Type" => "application/json"
    parent = get(opts, "parent", nothing)
    inventory = [(digest = a.digest, size = a.size, mediaType = "application/x-nifti") for a in values(assets)]
    created = JSON3.read(expect(201, "POST", "$server/api/v1/workspaces/$ws/projects/$proj/upload-sessions",
        [bearer, json],
        Vector{UInt8}(JSON3.write((
            manifestDigest = manifest_digest,
            manifestSize = length(manifest_bytes),
            parent = parent,
            assets = inventory,
        )))))
    by_digest = Dict(a.digest => a for a in values(assets))
    for instr in created.missing
        a = get(by_digest, instr.digest, nothing)
        a === nothing && fail("server asked for $(instr.digest), which is not in the inventory")
        method = haskey(instr, :method) ? String(instr.method) : "PUT"
        headers = Pair{String,String}[]
        # the bearer only belongs on the control plane; a signed object-store URL
        # carries its own authorization in `headers`
        startswith(String(instr.url), server) && push!(headers, bearer)
        if haskey(instr, :headers)
            for (k, v) in pairs(instr.headers)
                push!(headers, String(k) => String(v))
            end
        end
        any(h -> lowercase(h.first) == "content-type", headers) ||
            push!(headers, "Content-Type" => "application/octet-stream")
        bytes = read(joinpath(bundle_dir, "assets", a.file))
        status, text = request(method, String(instr.url), headers, bytes)
        200 <= status < 300 || fail("$method $(instr.url) returned HTTP $status: $(strip(text))")
        println("uploaded ", a.file, " ", a.digest)
    end
    expect(204, "PUT", String(created.manifestUrl), [bearer, "Content-Type" => "application/json"], manifest_bytes)
    commit = JSON3.read(expect(201, "POST", "$server/api/v1/upload-sessions/$(created.sessionId)/commit",
        [bearer, json], Vector{UInt8}(JSON3.write((message = get(opts, "message", "julia producer"),)))))
    String(commit.digest) == manifest_digest ||
        fail("server digest $(commit.digest) differs from ours $manifest_digest")
    println("revision ", commit.revisionId)
    println("server-digest ", commit.digest)
    println("viewUrl ", commit.viewUrl)
    detail = JSON3.read(expect(200, "GET", "$server/api/v1/revisions/$(commit.revisionId)", [bearer], nothing))
    for r in detail.renditions
        println("rendition ", r.assetId, " ", r.status)
    end
    all(r -> String(r.status) == "ready", detail.renditions) || fail("not every rendition is ready")
end

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

function main()
    opts = parse_args(ARGS)
    out = opts["out"]
    mkpath(joinpath(out, "assets"))
    assets = Dict{String,Any}()
    for (id, kind) in (("t1", :t1), ("speech-effect", :effect), ("speech-t", :t), ("speech-z", :z))
        bytes = nifti_bytes(synthetic(kind))
        file = id * ".nii"
        write(joinpath(out, "assets", file), bytes)
        assets[id] = (file = file, digest = digest(bytes), size = length(bytes))
    end
    # pretty-printed for readers; the digest covers exactly these bytes
    pretty = IOBuffer()
    JSON3.pretty(pretty, JSON3.write(manifest(assets)))
    text = String(take!(pretty))
    manifest_bytes = Vector{UInt8}(codeunits(text * "\n"))
    write(joinpath(out, "manifest.json"), manifest_bytes)
    d = digest(manifest_bytes)
    write(joinpath(out, "manifest.sha256"), d[8:end] * "\n")
    println("digest ", d)
    haskey(opts, "server") && publish(opts, out, manifest_bytes, d, assets)
end

main()
