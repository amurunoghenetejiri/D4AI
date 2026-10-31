export type SessionUser = {
  id?: string;
  email?: string;
  fullName?: string;
  roles?: string[];
};

export function useSessionUser(): { data: SessionUser | null } {
  return { data: null };
}
