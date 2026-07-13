class Protocol:
    PROTOCOL_VERSION = 1
    API_VERSION = 1

    @classmethod
    def supports(cls, version: int) -> bool:
        return version == cls.PROTOCOL_VERSION

    @classmethod
    def supported_versions(cls) -> list:
        return [cls.PROTOCOL_VERSION]

    @classmethod
    def current_version(cls) -> int:
        return cls.PROTOCOL_VERSION
