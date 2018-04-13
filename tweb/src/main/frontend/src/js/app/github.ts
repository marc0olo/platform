import {Utils} from './utils';

export class GithubUser {
    login: string;
    id: number;
    avatar_url: string;
    gravatar_id: string;
    url: string;
    html_url: string;
    followers_url: string;
    following_url: string;
    gists_url: string;
    starred_url: string;
    subscriptions_url: string;
    organizations_url: string;
    repos_url: string;
    events_url: string;
    received_events_url: string;
    type: string;
    site_admin: boolean;
}

export class GithubIssue {
    url: string;
    repository_url: string;
    labels_url: string;
    comments_url: string;
    events_url: string;
    html_url: string;
    id: number;
    number: number;
    title: string;
    user: GithubUser;
    labels: string[];
    state: string;
    locked: boolean;
    assignee: boolean;
    assignees: string[];
    milestone: string;
    comments: number;
    created_at: string; //2018-03-21T10:28:54Z,
    updated_at: string; // 2018-03-22T17:04:24Z,
    closed_at: string;
    author_association: string;
    body: string;
    closed_by: string;
}

export class Github {

    public static async validateLink(link: string): Promise<boolean> {
        return (await Github.getGithubInfo(link)) != null;
    }

    public static getGithubInfo(link: string): Promise<GithubIssue> {
        let matches = /^https:\/\/github\.com\/(.+)\/(.+)\/issues\/(\d+)$/.exec(link);
        if (matches && matches.length >= 4) {
            let url = `https://api.github.com/repos/${matches[1]}/${matches[2]}/issues/${matches[3]}`;
            return Utils.fetchJSON(url)
                .then(res => res ? Object.assign(new GithubIssue(), res) : null);
        } else {
            return Promise.resolve(null);
        }
    }

}