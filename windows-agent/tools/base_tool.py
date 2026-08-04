from abc import ABC, abstractmethod

class BaseTool(ABC):
    @property
    @abstractmethod
    def name(self) -> str:
        """The tool identifier, e.g., 'cmd' or 'filesystem'"""
        pass

    @abstractmethod
    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        """
        Executes a specific action for this tool.
        send_progress is an async callable: send_progress(message: str)
        Returns a dict payload containing status and output/results.
        """
        pass
