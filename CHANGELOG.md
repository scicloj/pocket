# Changelog

All notable changes to Pocket will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- `cached` and `caching-fn` now accept **keywords** in addition to vars (e.g., `(cached :train split-c)`)
- `origin-story-mermaid` preserves `:` prefix for keyword function nodes

## [0.1.0] - 2026-02-06

Initial release focusing on filesystem-based caching for data science pipelines.
