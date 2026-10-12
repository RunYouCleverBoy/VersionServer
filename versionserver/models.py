from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


class Role(str, Enum):
    Admin = "Admin"
    Client = "Client"


class PersistedUser(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    id: str
    password_hash: str = Field(alias="passwordHash")
    role: Role


class PersistedGrant(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    user_id: str = Field(alias="userId")
    project: str


class PersistedArtifact(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    project: str
    version: str
    file_name: str = Field(alias="fileName")
    uploaded_at: str = Field(default="1970-01-01T00:00:00Z", alias="uploadedAt")


class PersistedState(BaseModel):
    model_config = ConfigDict(extra="ignore")

    users: list[PersistedUser] = Field(default_factory=list)
    grants: list[PersistedGrant] = Field(default_factory=list)
    artifacts: list[PersistedArtifact] = Field(default_factory=list)


class VersionSummary(BaseModel):
    name: str
    latest: bool
