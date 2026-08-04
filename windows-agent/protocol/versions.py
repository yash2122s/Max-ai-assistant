class Protocol:
    PROTOCOL_VERSION = "2.1"
    API_VERSION = 1

    @classmethod
    def supports(cls, version) -> bool:
        # Support both legacy (1) and new (2.1) client versions
        return str(version) in ["1", "2.1"]

    @classmethod
    def supported_versions(cls) -> list:
        return ["1", "2.1"]

    @classmethod
    def current_version(cls) -> str:
        return cls.PROTOCOL_VERSION

